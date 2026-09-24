/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.*;

import denso.table.editor.DensoStructureApplier;
import denso.table.editor.GhidraTablesPlugin;
import denso.table.editor.DensoTableScanner;
import denso.table.editor.model.*;
import docking.ActionContext;
import docking.action.*;
import docking.widgets.table.GFilterTable;
import ghidra.app.services.GoToService;
import ghidra.framework.plugintool.ComponentProviderAdapter;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Program;
import ghidra.util.Msg;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.*;
import resources.Icons;

/**
 * The dockable provider that shows a filterable list of all Denso calibration
 * tables discovered in the current program.
 *
 * <ul>
 *   <li>Clicking <b>Scan ROM</b> launches a background scan.</li>
 *   <li>Double-clicking the <b>Header Address</b> column navigates to that address.</li>
 *   <li>Double-clicking any other column opens the table editor.</li>
 *   <li>Selecting one or more rows and clicking <b>Apply Structure</b> marks the
 *       relevant addresses with Ghidra data types.</li>
 * </ul>
 */
public class DensoTableListProvider extends ComponentProviderAdapter {

    private final GhidraTablesPlugin plugin;

    private JComponent root;
    private GFilterTable<DensoTable> filterTable;
    private DensoTableListModel model;
    private JLabel programLabel;
    private JLabel summaryLabel;

    private JLabel statusLabel;
    private DockingAction scanAction;
    private DockingAction applyStructureAction;
    private final AtomicLong scanGeneration = new AtomicLong();
    private List<DensoTable> currentTables = List.of();
    private final List<GhidraTablesEditorFrame> editors = new ArrayList<>();
    private boolean disposed;
    private volatile TaskMonitor activeScanMonitor;

    // ── Construction ──────────────────────────────────────────────────────────

    public DensoTableListProvider(GhidraTablesPlugin plugin) {
        super(plugin.getTool(), "GhidraTables", plugin.getName());
        this.plugin = plugin;

        model = new DensoTableListModel(plugin.getTool());
        root  = build();
        createActions();

        setTitle("GhidraTables");
    }

    // ── ComponentProviderAdapter ──────────────────────────────────────────────

    @Override
    public JComponent getComponent() { return root; }

    public void dispose() {
        disposed = true;
        cancelScan();
        for (GhidraTablesEditorFrame editor : List.copyOf(editors)) editor.dispose();
        editors.clear();
        filterTable.dispose();
        removeFromTool();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void scanProgram(Program program) {
        if (disposed) return;
        if (program == null || program.isClosed()) {
            programChanged(null);
            return;
        }
        cancelScan();

        long scanId = scanGeneration.incrementAndGet();
        Program scannedProgram = program;
        statusLabel.setText("Scanning...");
        scanAction.setEnabled(false);

        Task task = new Task("Scanning for Denso tables", true, true, false) {
            @Override
            public void run(TaskMonitor monitor) {
                if (scanGeneration.get() != scanId) return;
                activeScanMonitor = monitor;
                // Keep the program alive while the worker reads it, even if its tab closes.
                boolean retained = scannedProgram.addConsumer(this);
                try {
                    if (!retained) {
                        SwingUtilities.invokeLater(() ->
                                completeScan(scanId, scannedProgram, null, null, true));
                        return;
                    }
                    if (scanGeneration.get() != scanId) return;
                    List<DensoTable> tables = DensoTableScanner.scan(scannedProgram, monitor);
                    SwingUtilities.invokeLater(() ->
                            completeScan(scanId, scannedProgram, tables, null, false));
                }
                catch (CancelledException ex) {
                    SwingUtilities.invokeLater(() ->
                            completeScan(scanId, scannedProgram, null, null, true));
                }
                catch (RuntimeException ex) {
                    SwingUtilities.invokeLater(() ->
                            completeScan(scanId, scannedProgram, null, ex, false));
                } finally {
                    if (activeScanMonitor == monitor) activeScanMonitor = null;
                    if (retained) scannedProgram.release(this);
                }
            }
        };

        new TaskLauncher(task, plugin.getTool().getToolFrame());
    }

    public void programChanged(Program program) {
        cancelScan();
        currentTables = List.of();
        model.setTables(List.of());
        updateOverview(program, currentTables);
        statusLabel.setText(program == null
                ? "No program loaded - click Scan to begin."
                : "Program loaded - click Scan to find tables.");
        if (scanAction != null) {
            scanAction.setEnabled(program != null);
        }
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private JComponent build() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBackground(GhidraTheme.panelBackground());
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        panel.add(buildOverviewPanel(), BorderLayout.NORTH);
        panel.add(buildTablePanel(), BorderLayout.CENTER);
        panel.add(buildStatusPanel(), BorderLayout.SOUTH);

        return panel;
    }

    private JComponent buildOverviewPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 0));
        panel.setBackground(GhidraTheme.surfaceBackground());
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, GhidraTheme.subtleBorderColor()),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));

        JLabel title = new JLabel("GhidraTables");
        title.setForeground(GhidraTheme.primaryForeground());
        title.setFont(GhidraTheme.titleFont());

        programLabel = new JLabel("No active program");
        programLabel.setForeground(GhidraTheme.secondaryForeground());
        programLabel.setFont(GhidraTheme.smallFont());

        summaryLabel = new JLabel("0 tables");
        summaryLabel.setForeground(GhidraTheme.primaryForeground());
        summaryLabel.setFont(GhidraTheme.labelFont());

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.add(title);
        left.add(Box.createVerticalStrut(2));
        left.add(programLabel);

        panel.add(left, BorderLayout.WEST);
        panel.add(summaryLabel, BorderLayout.EAST);
        return panel;
    }

    private Component buildTablePanel() {
        filterTable = new GFilterTable<>(model);
        filterTable.setBackground(GhidraTheme.panelBackground());
        filterTable.getFilterPanel().setBackground(GhidraTheme.panelBackground());

        JTable jt = filterTable.getTable();
        jt.setBackground(GhidraTheme.tableBackground());
        jt.setForeground(GhidraTheme.tableForeground());
        jt.setGridColor(GhidraTheme.tableGridColor());
        jt.setSelectionBackground(GhidraTheme.tableSelectionBackground());
        jt.setSelectionForeground(GhidraTheme.tableSelectionForeground());
        jt.setShowVerticalLines(false);
        jt.setShowHorizontalLines(false);
        jt.setIntercellSpacing(new Dimension(0, 1));
        jt.setRowHeight(26);
        jt.setFont(GhidraTheme.tableFont());
        jt.getTableHeader().setBackground(GhidraTheme.tableHeaderBackground());
        jt.getTableHeader().setForeground(GhidraTheme.tableHeaderForeground());
        jt.getTableHeader().setFont(GhidraTheme.tableHeaderFont());

        jt.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int viewRow = jt.rowAtPoint(e.getPoint());
                    if (viewRow < 0) return;
                    int viewCol  = jt.columnAtPoint(e.getPoint());
                    if (viewCol < 0) return;
                    jt.setRowSelectionInterval(viewRow, viewRow);
                    String colName = jt.getColumnModel().getColumn(viewCol)
                                       .getHeaderValue().toString();
                    if (DensoTableListModel.HEADER_ADDRESS_COLUMN.equals(colName)) {
                        goToSelectedAddress();
                    } else {
                        openSelectedTable();
                    }
                }
            }
        });

        jt.getInputMap(JComponent.WHEN_FOCUSED)
          .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openEditor");
        jt.getActionMap().put("openEditor", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { openSelectedTable(); }
        });

        return filterTable;
    }

    private Component buildStatusPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(GhidraTheme.surfaceBackground());
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, GhidraTheme.subtleBorderColor()),
                BorderFactory.createEmptyBorder(6, 10, 5, 10)));

        statusLabel = new JLabel("No program loaded - click Scan to begin.");
        statusLabel.setForeground(GhidraTheme.primaryForeground());
        statusLabel.setFont(GhidraTheme.labelFont());

        JLabel hint = new JLabel("Enter: edit  |  Double-click address: navigate  |  Double-click row: edit");
        hint.setForeground(GhidraTheme.secondaryForeground());
        hint.setFont(GhidraTheme.smallFont());

        p.add(statusLabel, BorderLayout.WEST);
        p.add(hint,        BorderLayout.EAST);

        return p;
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void createActions() {
        // Scan
        scanAction = new DockingAction("Scan ROM for Denso tables", getName()) {
            @Override
            public void actionPerformed(ActionContext context) {
                scanProgram(plugin.getCurrentProgram());
            }
        };
        scanAction.setToolBarData(new ToolBarData(Icons.REFRESH_ICON, null));
        scanAction.setDescription("Scan the current ROM for supported Denso calibration tables");
        scanAction.markHelpUnnecessary();
        addLocalAction(scanAction);

        // Open editor
        DockingAction openAction = new DockingAction("Open Table Editor", getName()) {
            @Override
            public void actionPerformed(ActionContext context) { openSelectedTable(); }

            @Override
            public boolean isEnabledForContext(ActionContext context) {
                return !filterTable.getSelectedRowObjects().isEmpty();
            }
        };
        openAction.setToolBarData(new ToolBarData(Icons.NAVIGATE_ON_INCOMING_EVENT_ICON, null));
        openAction.setDescription("Open the selected table in the editor");
        openAction.setPopupMenuData(new MenuData(new String[]{"Edit Table"}));
        openAction.markHelpUnnecessary();
        addLocalAction(openAction);

        // Apply structure
        applyStructureAction = new DockingAction("Apply Table Structures", getName()) {
            @Override
            public void actionPerformed(ActionContext context) { applyStructureToSelected(); }

            @Override
            public boolean isEnabledForContext(ActionContext context) {
                return plugin.getCurrentProgram() != null
                        && !filterTable.getSelectedRowObjects().isEmpty();
            }
        };
        applyStructureAction.setToolBarData(new ToolBarData(Icons.STRONG_WARNING_ICON, null));
        applyStructureAction.setDescription(
                "Apply Ghidra data type structures to the selected table addresses");
        applyStructureAction.setPopupMenuData(
                new MenuData(new String[]{"Apply Structure"}));
        applyStructureAction.markHelpUnnecessary();
        addLocalAction(applyStructureAction);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void openSelectedTable() {
        List<DensoTable> sel = filterTable.getSelectedRowObjects();
        if (sel.isEmpty()) return;

        Program prog = plugin.getCurrentProgram();
        if (prog == null || prog.isClosed()) return;
        Window owner = SwingUtilities.getWindowAncestor(filterTable);
        for (DensoTable table : sel) {
            GhidraTablesEditorFrame existing = editors.stream()
                    .filter(e -> e.getProgram() == prog && e.getHeaderAddress() == table.getHeaderAddress())
                    .findFirst().orElse(null);
            if (existing != null) {
                existing.setExtendedState(existing.getExtendedState() & ~Frame.ICONIFIED);
                existing.setVisible(true);
                existing.toFront();
                existing.requestFocus();
                continue;
            }
            try {
                GhidraTablesEditorFrame frame = new GhidraTablesEditorFrame(
                        table.copy(), prog, plugin.getTool(), owner);
                editors.add(frame);
                frame.addWindowListener(new WindowAdapter() {
                    @Override public void windowClosed(WindowEvent e) { editors.remove(frame); }
                });
                frame.setSavedListener(() -> {
                    if (disposed || plugin.getCurrentProgram() != prog) return;
                    currentTables = currentTables.stream()
                            .map(t -> t.getHeaderAddress() == frame.getHeaderAddress()
                                    ? frame.getTableSnapshot() : t).toList();
                    model.setTables(currentTables);
                });
                frame.setVisible(true);
            } catch (RuntimeException ex) {
                Msg.showError(this, owner, "Open Table", ex.getMessage(), ex);
            }
        }
    }

    private void cancelScan() {
        scanGeneration.incrementAndGet();
        TaskMonitor monitor = activeScanMonitor;
        if (monitor != null) monitor.cancel();
    }

    public boolean canCloseEditors(Program program) {
        boolean unsaved = editors.stream().anyMatch(e ->
                (program == null || e.getProgram() == program) && e.hasUnsavedChanges());
        return !unsaved || JOptionPane.showConfirmDialog(root,
                "Table editors have unsaved changes. Discard them and close?", "Unsaved Table Changes",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    public void programClosed(Program program) {
        for (GhidraTablesEditorFrame editor : List.copyOf(editors)) {
            if (editor.getProgram() == program) {
                editors.remove(editor);
                editor.dispose();
            }
        }
    }

    private void goToSelectedAddress() {
        List<DensoTable> sel = filterTable.getSelectedRowObjects();
        if (sel.isEmpty()) return;

        Program prog = plugin.getCurrentProgram();
        if (prog == null) return;

        GoToService goTo = plugin.getTool().getService(GoToService.class);
        if (goTo == null) {
            Msg.showWarn(this, null, "Navigation", "GoToService not available.");
            return;
        }

        // Navigate to the first selected table's header address
        DensoTable t = sel.get(0);
        Address addr = prog.getAddressFactory()
                .getDefaultAddressSpace()
                .getAddress(t.getHeaderAddress());
        goTo.goTo(addr);
    }

    private void applyStructureToSelected() {
        List<DensoTable> sel = filterTable.getSelectedRowObjects();
        if (sel.isEmpty()) return;

        Program prog = plugin.getCurrentProgram();
        if (prog == null) {
            Msg.showWarn(this, null, "No Program", "No program is currently loaded.");
            return;
        }

        Window owner = SwingUtilities.getWindowAncestor(filterTable);
        DensoStructureApplier.showDialogAndApply(prog, sel, owner);
    }

    private void completeScan(long scanId, Program scannedProgram, List<DensoTable> tables,
            RuntimeException error, boolean cancelled) {
        if (!isCurrentScan(scanId, scannedProgram)) {
            return;
        }

        scanAction.setEnabled(true);

        if (cancelled) {
            statusLabel.setText("Scan cancelled.");
            return;
        }

        if (error != null) {
            statusLabel.setText("Scan failed.");
            Msg.showError(this, null, "Scan Error",
                    "Failed to scan the current program.", error);
            return;
        }

        currentTables = List.copyOf(tables);
        updateOverview(scannedProgram, currentTables);
        model.setTables(tables);
        statusLabel.setText(String.format(
                "Found %d table%s  (1D: %d  2D: %d)",
                tables.size(),
                tables.size() == 1 ? "" : "s",
                tables.stream().filter(t -> !t.is2D()).count(),
                tables.stream().filter(DensoTable::is2D).count()));
    }

    private boolean isCurrentScan(long scanId, Program scannedProgram) {
        return !disposed && !scannedProgram.isClosed()
                && scanGeneration.get() == scanId && plugin.getCurrentProgram() == scannedProgram;
    }

    private void updateOverview(Program program, List<DensoTable> tables) {
        if (programLabel == null) {
            return;
        }
        programLabel.setText(program == null
                ? "No active program"
                : "Program: " + program.getName());
        long oneD = tables.stream().filter(t -> !t.is2D()).count();
        long twoD = tables.stream().filter(DensoTable::is2D).count();
        summaryLabel.setText(String.format("%d total  |  %d 1D  |  %d 2D", tables.size(), oneD, twoD));
    }
}
