/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.List;
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
        filterTable.dispose();
        removeFromTool();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void scanProgram(Program program) {
        if (program == null) {
            model.setTables(List.of());
            statusLabel.setText("No program loaded.");
            return;
        }

        long scanId = scanGeneration.incrementAndGet();
        Program scannedProgram = program;
        statusLabel.setText("Scanning...");
        scanAction.setEnabled(false);

        Task task = new Task("Scanning for Denso tables", true, true, false) {
            @Override
            public void run(TaskMonitor monitor) {
                try {
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
                }
            }
        };

        new TaskLauncher(task, plugin.getTool().getToolFrame());
    }

    public void programChanged(Program program) {
        scanGeneration.incrementAndGet();
        currentTables = List.of();
        model.setTables(List.of());
        updateOverview(program, currentTables);
        statusLabel.setText(program == null
                ? "No program loaded - click Scan to begin."
                : "Program loaded - click Scan to find tables.");
        if (scanAction != null) {
            scanAction.setEnabled(true);
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
                BorderFactory.createMatteBorder(0, 0, 1, 0, GhidraTheme.borderColor()),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));

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
        jt.setIntercellSpacing(new Dimension(0, 1));
        jt.setRowHeight(24);
        jt.setFont(GhidraTheme.tableFont());
        jt.getTableHeader().setBackground(GhidraTheme.tableHeaderBackground());
        jt.getTableHeader().setForeground(GhidraTheme.tableHeaderForeground());
        jt.getTableHeader().setFont(GhidraTheme.tableHeaderFont());

        jt.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int viewCol  = jt.columnAtPoint(e.getPoint());
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
                BorderFactory.createMatteBorder(1, 0, 0, 0, GhidraTheme.borderColor()),
                BorderFactory.createEmptyBorder(5, 8, 4, 8)));

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
        Window owner = SwingUtilities.getWindowAncestor(filterTable);

        for (DensoTable t : sel) {
            DensoTable detachedTable = t.copy();
            GhidraTablesEditorFrame frame = new GhidraTablesEditorFrame(
                    detachedTable, prog, plugin.getTool(), owner);
            frame.setVisible(true);
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
        return scanGeneration.get() == scanId && plugin.getCurrentProgram() == scannedProgram;
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
