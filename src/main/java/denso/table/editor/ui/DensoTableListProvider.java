/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.List;
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

    private JLabel statusLabel;
    private DockingAction scanAction;
    private DockingAction applyStructureAction;

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

        statusLabel.setText("Scanning...");
        scanAction.setEnabled(false);

        Task task = new Task("Scanning for Denso tables", true, true, false) {
            @Override
            public void run(TaskMonitor monitor) throws CancelledException {
                List<DensoTable> tables = DensoTableScanner.scan(program, monitor);
                SwingUtilities.invokeLater(() -> {
                    model.setTables(tables);
                    statusLabel.setText(String.format(
                            "Found %d table%s  (1D: %d  2D: %d)",
                            tables.size(),
                            tables.size() == 1 ? "" : "s",
                            tables.stream().filter(t -> !t.is2D()).count(),
                            tables.stream().filter(DensoTable::is2D).count()));
                    scanAction.setEnabled(true);
                });
            }
        };

        new TaskLauncher(task, plugin.getTool().getToolFrame());
    }

    public void programChanged(Program program) {
        model.setTables(List.of());
        statusLabel.setText(program == null
                ? "No program loaded - click Scan to begin."
                : "Program loaded - click Scan to find tables.");
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private JComponent build() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBackground(new Color(28, 36, 48));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        panel.add(buildTablePanel(), BorderLayout.CENTER);
        panel.add(buildStatusPanel(), BorderLayout.SOUTH);

        return panel;
    }

    private Component buildTablePanel() {
        filterTable = new GFilterTable<>(model);

        JTable jt = filterTable.getTable();
        jt.setBackground(new Color(22, 30, 44));
        jt.setForeground(new Color(200, 215, 230));
        jt.setGridColor(new Color(45, 56, 72));
        jt.setRowHeight(22);
        jt.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        jt.getTableHeader().setBackground(new Color(38, 50, 66));
        jt.getTableHeader().setForeground(new Color(160, 190, 220));

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
        p.setBackground(new Color(22, 30, 42));
        p.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));

        statusLabel = new JLabel("No program loaded - click Scan to begin.");
        statusLabel.setForeground(new Color(130, 160, 190));
        statusLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

        JLabel hint = new JLabel("dbl-click addr col: navigate  |  dbl-click row: edit");
        hint.setForeground(new Color(80, 100, 130));
        hint.setFont(new Font(Font.MONOSPACED, Font.ITALIC, 10));

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
            GhidraTablesEditorFrame frame = new GhidraTablesEditorFrame(
                    t, prog, plugin.getTool(), owner);
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
}
