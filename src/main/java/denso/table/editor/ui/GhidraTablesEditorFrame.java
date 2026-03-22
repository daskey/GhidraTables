/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor.ui;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.Arrays;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;

import denso.table.editor.DensoStructureApplier;
import denso.table.editor.model.*;
import ghidra.app.services.GoToService;
import ghidra.framework.plugintool.PluginTool;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.*;
import ghidra.util.Msg;

/**
 * A standalone pop-out window for viewing and editing a single Denso
 * calibration table.
 *
 * <p>Values in the model are stored as <em>raw</em> ROM values.
 * The MAC (scale × offset) is applied on display and inverted on edit.
 * Editing MAC fields updates header parameters without touching raw data.
 */
public class GhidraTablesEditorFrame extends JFrame {

    // ── Theme-derived fonts ───────────────────────────────────────────────────
    private static final Font UI_FONT        = GhidraTheme.tableFont();
    private static final Font UI_FONT_SMALL  = GhidraTheme.smallFont();
    private static final Font UI_FONT_BOLD   = GhidraTheme.boldFont();
    private static final Font UI_FONT_TITLE  = GhidraTheme.titleFont();
    private static final int DEFAULT_INSPECTOR_WIDTH = 248;

    private enum TableDensity {
        AUTO("Auto"),
        COMPACT("Compact"),
        COMFORTABLE("Comfortable");

        private final String label;

        TableDensity(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum OperationAxis {
        AUTO("Auto"),
        ROWS("Rows"),
        COLUMNS("Columns");

        private final String label;

        OperationAxis(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class OperationStats {
        int spans;
        int cells;

        boolean changed() {
            return cells > 0;
        }
    }

    // ── Model ─────────────────────────────────────────────────────────────────
    private final DensoTable table;
    private final Program program;
    private final PluginTool tool;

    // ── UI components ─────────────────────────────────────────────────────────
    private JTable grid;
    private JTable rowHeaderTable;
    private AbstractTableModel tableModel;
    private AbstractTableModel rowHeaderModel;
    private HeatMapCellRenderer renderer;
    private MultiEditTableCellEditor editor;
    private JScrollPane tableScrollPane;
    private JScrollPane inspectorScrollPane;
    private JSplitPane splitPane;
    private JLabel cornerLabel;

    private JLabel statusLabel;
    private JTextArea selectionSummaryLabel;
    private JLabel tableStatsLabel;
    private JButton saveBtn;
    private JButton revertBtn;
    private JToggleButton inspectorToggle;
    private JComboBox<TableDensity> densityCombo;
    private JComboBox<OperationAxis> axisCombo;

    private JTextField multField;
    private JTextField offField;
    private JTextArea macExprLabel;

    /** True whenever in-memory state differs from the last saved ROM state. */
    private boolean dirty = false;

    /**
     * Set to true during programmatic data loads (initial load, revert) to
     * suppress the dirty-marking side-effect of fireTableDataChanged.
     */
    private boolean loading = false;
    private int currentCellWidth = 72;
    private int currentRowHeight = 26;
    private int currentRowHeaderWidth = 72;
    private int lastDividerLocation = -1;

    // ── Construction ──────────────────────────────────────────────────────────

    public GhidraTablesEditorFrame(DensoTable table, Program program, PluginTool tool, Window owner) {
        super(table.getName() + " - GhidraTables");
        this.table   = table;
        this.program = program;
        this.tool    = tool;

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { handleClose(); }
        });

        setLayout(new BorderLayout());
        setBackground(GhidraTheme.panelBackground());

        add(buildInfoBar(),     BorderLayout.NORTH);
        add(buildCenterPanel(), BorderLayout.CENTER);
        add(buildActionBar(),   BorderLayout.SOUTH);

        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                SwingUtilities.invokeLater(() -> {
                    applyTableDensity();
                    restoreInspectorLayout();
                });
            }
        });

        // Always read fresh from ROM so the display is never stale from scan data
        loadFromRom();
        syncMacUi();
        refreshGridFromModel();

        pack();
        setMinimumSize(new Dimension(520, 320));
        setPreferredSize(computePreferredSize());
        pack();

        if (owner != null) setLocationRelativeTo(owner);
        else               setLocationByPlatform(true);

        SwingUtilities.invokeLater(() -> {
            restoreInspectorLayout();
            applyTableDensity();
            if (isLargeTable()) {
                setInspectorVisible(false);
            }
            updateStatus();
        });
    }

    // =========================================================================
    // Info bar (top)
    // =========================================================================

    private JPanel buildInfoBar() {
        JPanel bar = new JPanel(new BorderLayout(16, 0));
        bar.setBackground(GhidraTheme.surfaceBackground());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, GhidraTheme.borderColor()),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));

        JPanel titleBox = new JPanel();
        titleBox.setOpaque(false);
        titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));

        JLabel nameLabel = new JLabel(table.getName());
        nameLabel.setForeground(GhidraTheme.primaryForeground());
        nameLabel.setFont(UI_FONT_TITLE);

        JLabel addrLabel = new JLabel(table.getAddressHex());
        addrLabel.setForeground(GhidraTheme.linkForeground());
        addrLabel.setFont(GhidraTheme.labelFont());
        addrLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addrLabel.setToolTipText("Click to navigate in Ghidra  |  Right-click for menu");

        JPopupMenu popup = new JPopupMenu();
        popup.add(new AbstractAction("Go to Address in Ghidra") {
            @Override public void actionPerformed(ActionEvent e) { goToAddress(); }
        });
        addrLabel.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) goToAddress();
            }
            @Override public void mousePressed(MouseEvent e)  { if (e.isPopupTrigger()) popup.show(addrLabel, e.getX(), e.getY()); }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) popup.show(addrLabel, e.getX(), e.getY()); }
        });

        JLabel subtitle = new JLabel("Raw values are stored in ROM; displayed values apply MAC.");
        subtitle.setForeground(GhidraTheme.secondaryForeground());
        subtitle.setFont(UI_FONT_SMALL);

        titleBox.add(nameLabel);
        titleBox.add(Box.createVerticalStrut(4));
        titleBox.add(addrLabel);
        titleBox.add(Box.createVerticalStrut(3));
        titleBox.add(subtitle);

        JPanel chips = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        chips.setOpaque(false);
        chips.add(makeChip(table.is2D() ? "2D" : "1D", GhidraTheme.secondaryForeground()));
        chips.add(makeChip(table.getDimensions(), GhidraTheme.tableSelectionBackground()));
        chips.add(makeChip(table.getDataType().getDisplayName(), GhidraTheme.linkForeground()));
        if (table.isHasMAC()) chips.add(makeChip("MAC", GhidraTheme.linkForeground()));

        bar.add(titleBox, BorderLayout.WEST);
        bar.add(chips,    BorderLayout.EAST);
        return bar;
    }

    private JLabel makeChip(String text, Color accent) {
        JLabel chip = new JLabel(" " + text + " ");
        chip.setFont(UI_FONT_SMALL.deriveFont(Font.BOLD));
        chip.setForeground(GhidraTheme.primaryForeground());
        chip.setOpaque(true);
        chip.setBackground(GhidraTheme.mix(GhidraTheme.cardBackground(), accent, 0.14f));
        chip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GhidraTheme.mix(GhidraTheme.borderColor(), accent, 0.28f)),
                BorderFactory.createEmptyBorder(2, 5, 2, 5)));
        return chip;
    }

    // =========================================================================
    // Action bar (bottom)
    // =========================================================================

    private JPanel buildActionBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBackground(GhidraTheme.surfaceBackground());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, GhidraTheme.borderColor()),
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btns.setOpaque(false);

        saveBtn   = makeBtn("Save to ROM", e -> applyChanges());
        revertBtn = makeBtn("Revert", e -> revertChanges());

        saveBtn.setEnabled(false);
        revertBtn.setEnabled(false);

        btns.add(saveBtn);
        btns.add(revertBtn);

        statusLabel = new JLabel("Ready to edit");
        statusLabel.setForeground(GhidraTheme.primaryForeground());
        statusLabel.setFont(GhidraTheme.labelFont());

        bar.add(statusLabel, BorderLayout.WEST);
        bar.add(btns,        BorderLayout.EAST);
        return bar;
    }

    private JButton makeBtn(String label, ActionListener al) {
        JButton btn = new JButton(label);
        btn.setFont(UI_FONT_BOLD);
        btn.setFocusPainted(false);
        btn.setMargin(new Insets(4, 12, 4, 12));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addActionListener(al);
        return btn;
    }

    // =========================================================================
    // Center panel (table + edit ops + MAC)
    // =========================================================================

    private JComponent buildCenterPanel() {
        JPanel workspace = new JPanel(new BorderLayout());
        workspace.setBackground(GhidraTheme.panelBackground());
        workspace.add(buildTableToolBar(), BorderLayout.NORTH);
        workspace.add(buildTablePanel(), BorderLayout.CENTER);

        inspectorScrollPane = new JScrollPane(buildInspectorPanel());
        inspectorScrollPane.setBorder(BorderFactory.createEmptyBorder());
        inspectorScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        inspectorScrollPane.getViewport().setBackground(GhidraTheme.surfaceBackground());

        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, workspace, inspectorScrollPane);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        splitPane.setResizeWeight(1.0);
        splitPane.setContinuousLayout(true);
        splitPane.setOneTouchExpandable(true);
        splitPane.setDividerSize(Math.max(8, UIManager.getInt("SplitPane.dividerSize")));
        return splitPane;
    }

    private JComponent buildTableToolBar() {
        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setBackground(GhidraTheme.surfaceBackground());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, GhidraTheme.borderColor()),
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        controls.setOpaque(false);

        JButton interpBtn = makeBtn("Interpolate", e -> interpolateSelected());
        JButton smoothBtn = makeBtn("Smooth", e -> smoothSelected());
        JButton exportBtn = makeBtn("Export CSV", e -> exportCsv());
        JButton structureBtn = makeBtn("Apply Structure", e -> createStructure());

        interpBtn.setToolTipText("Interpolates between the first and last selected values along the chosen axis.");
        smoothBtn.setToolTipText("Applies a single-pass 3-point average along the chosen axis.");

        densityCombo = new JComboBox<>(TableDensity.values());
        densityCombo.setSelectedItem(TableDensity.AUTO);
        densityCombo.setFont(GhidraTheme.labelFont());
        densityCombo.setToolTipText("Auto shrinks large tables to fit more data in the current window.");
        densityCombo.addActionListener(e -> applyTableDensity());

        axisCombo = new JComboBox<>(table.is2D()
                ? OperationAxis.values()
                : new OperationAxis[]{OperationAxis.ROWS});
        axisCombo.setSelectedItem(table.is2D() ? OperationAxis.AUTO : OperationAxis.ROWS);
        axisCombo.setFont(GhidraTheme.labelFont());
        axisCombo.setToolTipText("Choose whether interpolate and smooth operate across rows or columns.");

        inspectorToggle = new JToggleButton("Inspector", true);
        inspectorToggle.setFont(GhidraTheme.labelFont());
        inspectorToggle.addActionListener(e -> setInspectorVisible(inspectorToggle.isSelected()));

        controls.add(interpBtn);
        controls.add(smoothBtn);
        controls.add(exportBtn);
        controls.add(structureBtn);
        controls.add(Box.createHorizontalStrut(10));
        controls.add(makeToolbarLabel("Density"));
        controls.add(densityCombo);
        if (table.is2D()) {
            controls.add(makeToolbarLabel("Axis"));
            controls.add(axisCombo);
        }
        controls.add(inspectorToggle);

        tableStatsLabel = new JLabel();
        tableStatsLabel.setFont(UI_FONT_SMALL);
        tableStatsLabel.setForeground(GhidraTheme.secondaryForeground());

        bar.add(controls, BorderLayout.WEST);
        bar.add(tableStatsLabel, BorderLayout.EAST);
        return bar;
    }

    private JLabel makeToolbarLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(UI_FONT_SMALL);
        label.setForeground(GhidraTheme.secondaryForeground());
        return label;
    }

    // =========================================================================
    // Inspector
    // =========================================================================

    private JComponent buildInspectorPanel() {
        JPanel sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBackground(GhidraTheme.surfaceBackground());
        sidebar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, GhidraTheme.borderColor()),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)));
        sidebar.setPreferredSize(new Dimension(DEFAULT_INSPECTOR_WIDTH, 0));

        sidebar.add(buildOverviewCard());
        sidebar.add(Box.createVerticalStrut(10));
        sidebar.add(buildSelectionCard());
        if (table.isHasMAC()) {
            sidebar.add(Box.createVerticalStrut(10));
            sidebar.add(buildMacCard());
        }
        sidebar.add(Box.createVerticalGlue());
        return sidebar;
    }

    private JComponent buildOverviewCard() {
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(buildMetaRow("Dimensions", table.getDimensions()));
        content.add(buildMetaRow("Data Type", table.getDataType().getDisplayName()));
        content.add(buildMetaRow("X Points", Integer.toString(table.getCountX())));
        if (table.is2D()) {
            content.add(buildMetaRow("Y Points",
                    Integer.toString(((DensoTable2D) table).getCountY())));
        }
        content.add(buildMetaRow("Header", table.getAddressHex()));
        content.add(buildMetaRow("MAC", table.isHasMAC() ? table.getMacExpression() : "None"));
        return buildInspectorCard("Overview", content);
    }

    private JComponent buildSelectionCard() {
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        selectionSummaryLabel = makeWrappedTextArea(
                "", GhidraTheme.labelFont(), GhidraTheme.primaryForeground());
        selectionSummaryLabel.setAlignmentX(0f);

        JTextArea hint = makeWrappedTextArea(
                "Select cells, then use the top toolbar to interpolate or smooth. " +
                "Auto density and hiding the inspector help on larger maps.",
                UI_FONT_SMALL, GhidraTheme.secondaryForeground());
        hint.setAlignmentX(0f);

        content.add(selectionSummaryLabel);
        content.add(Box.createVerticalStrut(8));
        content.add(hint);

        return buildInspectorCard("Selection", content);
    }

    private JComponent buildMacCard() {
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JLabel scaleLbl = new JLabel("Scale");
        scaleLbl.setAlignmentX(0f);
        scaleLbl.setForeground(GhidraTheme.secondaryForeground());
        scaleLbl.setFont(UI_FONT_SMALL);

        multField = makeMacField(String.valueOf(table.getMultiplier()));
        multField.setAlignmentX(0f);
        multField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

        JLabel offLbl = new JLabel("Offset");
        offLbl.setAlignmentX(0f);
        offLbl.setForeground(GhidraTheme.secondaryForeground());
        offLbl.setFont(UI_FONT_SMALL);

        offField = makeMacField(String.valueOf(table.getOffset()));
        offField.setAlignmentX(0f);
        offField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

        macExprLabel = makeWrappedTextArea(
                table.getMacExpression(),
                UI_FONT_SMALL.deriveFont(Font.ITALIC),
                GhidraTheme.secondaryForeground());
        macExprLabel.setAlignmentX(0f);

        ActionListener commit = e -> commitMacFields();
        multField.addActionListener(commit);
        offField.addActionListener(commit);
        multField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { commitMacFields(); }
        });
        offField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { commitMacFields(); }
        });

        content.add(scaleLbl);
        content.add(Box.createVerticalStrut(4));
        content.add(multField);
        content.add(Box.createVerticalStrut(8));
        content.add(offLbl);
        content.add(Box.createVerticalStrut(4));
        content.add(offField);
        content.add(Box.createVerticalStrut(10));
        content.add(macExprLabel);

        return buildInspectorCard("MAC", content);
    }

    private JComponent buildInspectorCard(String title, JComponent content) {
        JPanel card = new JPanel(new BorderLayout(0, 10));
        card.setAlignmentX(0f);
        card.setBackground(GhidraTheme.cardBackground());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GhidraTheme.borderColor()),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setForeground(GhidraTheme.primaryForeground());
        titleLabel.setFont(UI_FONT_BOLD);

        card.add(titleLabel, BorderLayout.NORTH);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private JComponent buildMetaRow(String key, String value) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setAlignmentX(0f);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JLabel keyLabel = new JLabel(key);
        keyLabel.setForeground(GhidraTheme.secondaryForeground());
        keyLabel.setFont(UI_FONT_SMALL);
        keyLabel.setAlignmentX(0f);

        JTextArea valueLabel = makeWrappedTextArea(
                value, UI_FONT_SMALL, GhidraTheme.primaryForeground());
        valueLabel.setAlignmentX(0f);

        row.add(keyLabel);
        row.add(Box.createVerticalStrut(2));
        row.add(valueLabel);
        row.add(Box.createVerticalStrut(6));
        return row;
    }

    private JTextArea makeWrappedTextArea(String text, Font font, Color color) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setWrapStyleWord(true);
        area.setLineWrap(true);
        area.setForeground(color);
        area.setFont(font);
        area.setBorder(BorderFactory.createEmptyBorder());
        area.setAlignmentX(0f);
        area.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return area;
    }

    // =========================================================================
    // Table panel
    // =========================================================================

    private JScrollPane buildTablePanel() {
        tableModel = table.is2D()
                ? new Table2DModel((DensoTable2D) table)
                : new Table1DModel((DensoTable1D) table);

        grid = new JTable(tableModel);
        configureGrid();

        tableScrollPane = new JScrollPane(grid);
        tableScrollPane.setBackground(GhidraTheme.tableBackground());
        tableScrollPane.getViewport().setBackground(GhidraTheme.tableBackground());
        tableScrollPane.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        tableScrollPane.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                SwingUtilities.invokeLater(GhidraTablesEditorFrame.this::applyTableDensity);
            }
        });

        if (table.is2D()) {
            rowHeaderTable = buildRowHeader((DensoTable2D) table);
            tableScrollPane.setRowHeaderView(rowHeaderTable);
            cornerLabel = buildCornerLabel();
            tableScrollPane.setCorner(JScrollPane.UPPER_LEFT_CORNER, cornerLabel);
        }
        else {
            rowHeaderTable = buildRowHeader((DensoTable1D) table);
            tableScrollPane.setRowHeaderView(rowHeaderTable);
        }

        return tableScrollPane;
    }

    private void configureGrid() {
        grid.setBackground(GhidraTheme.tableBackground());
        grid.setForeground(GhidraTheme.tableForeground());
        grid.setGridColor(GhidraTheme.tableGridColor());
        grid.setSelectionBackground(GhidraTheme.tableSelectionBackground());
        grid.setSelectionForeground(GhidraTheme.tableSelectionForeground());
        grid.setShowVerticalLines(false);
        grid.setIntercellSpacing(new Dimension(0, 1));
        grid.setRowHeight(currentRowHeight);
        grid.setFont(UI_FONT);
        grid.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        grid.setCellSelectionEnabled(true);
        grid.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        grid.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

        JTableHeader header = grid.getTableHeader();
        header.setBackground(GhidraTheme.tableHeaderBackground());
        header.setForeground(GhidraTheme.tableHeaderForeground());
        header.setFont(UI_FONT_BOLD);
        header.setReorderingAllowed(false);

        renderer = new HeatMapCellRenderer() {
            @Override
            protected CellRole getRoleFor(int row, int col) {
                if (table.is2D()) {
                    return CellRole.DATA;
                } else {
                    if (row == 0) return CellRole.X_HEADER;
                    return CellRole.DATA;
                }
            }
        };
        refreshHeatRange();
        grid.setDefaultRenderer(Object.class, renderer);

        editor = new MultiEditTableCellEditor();
        grid.setDefaultEditor(Object.class, editor);

        autoSizeColumns();

        grid.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updateStatus();
        });
        grid.getColumnModel().getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updateStatus();
        });

        tableModel.addTableModelListener(e -> {
            refreshHeatRange();
            if (!loading) markDirty();
            updateTableStats();
        });

        // ── Scroll wheel: adjust selected data cells ──────────────────────────
        grid.addMouseWheelListener(e -> {
            int[] selRows = grid.getSelectedRows();
            int[] selCols = grid.getSelectedColumns();

            boolean hasDataCells = false;
            outer:
            for (int r : selRows) {
                int mr = grid.convertRowIndexToModel(r);
                for (int c : selCols) {
                    if (tableModel.isCellEditable(mr, grid.convertColumnIndexToModel(c))) {
                        hasDataCells = true;
                        break outer;
                    }
                }
            }
            if (!hasDataCells) return; // let the scroll pane scroll normally

            double step;
            if (e.isShiftDown() && e.isControlDown()) step = 0.01;
            else if (e.isShiftDown())                 step = 10.0;
            else if (e.isControlDown())               step = 0.1;
            else                                      step = 1.0;

            adjustSelectedCells(-e.getWheelRotation() * step);
            e.consume();
        });

        // ── Right-click context menu ──────────────────────────────────────────
        JPopupMenu cellMenu = new JPopupMenu();

        JMenuItem miInterp   = new JMenuItem("Interpolate");
        JMenuItem miSmooth   = new JMenuItem("Smooth");

        for (JMenuItem mi : new JMenuItem[]{miInterp, miSmooth}) {
            mi.setFont(GhidraTheme.labelFont());
        }

        miInterp.addActionListener(e  -> interpolateSelected());
        miSmooth.addActionListener(e  -> smoothSelected());

        cellMenu.add(miInterp);
        cellMenu.addSeparator();
        cellMenu.add(miSmooth);

        grid.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { if (e.isPopupTrigger()) cellMenu.show(grid, e.getX(), e.getY()); }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) cellMenu.show(grid, e.getX(), e.getY()); }
        });

        updateTableStats();
    }

    // =========================================================================
    // Raw data accessors (model-coordinate row/col)
    // =========================================================================

    /**
     * Returns the raw (pre-MAC) value at the given model cell, or NaN if not a data cell.
     */
    private double getRawValue(int modelRow, int modelCol) {
        if (table.is2D()) {
            return ((DensoTable2D) table).getZ(modelRow, modelCol);
        } else {
            if (modelRow != 1) return Double.NaN;
            double[] ys = ((DensoTable1D) table).getValuesY();
            return (modelCol >= 0 && modelCol < ys.length) ? ys[modelCol] : Double.NaN;
        }
    }

    /** Sets the raw value at the given model cell. */
    private void setRawValue(int modelRow, int modelCol, double raw) {
        if (table.is2D()) {
            ((DensoTable2D) table).setZ(modelRow, modelCol, raw);
        } else {
            if (modelRow == 1) {
                double[] ys = ((DensoTable1D) table).getValuesY();
                if (modelCol >= 0 && modelCol < ys.length) ys[modelCol] = raw;
            }
        }
    }

    /**
     * Fires a full table data changed event and then restores the previous cell
     * selection (which JTable clears on fireTableDataChanged).
     */
    private void fireAndRestoreSelection() {
        int[] selRows = grid.getSelectedRows();
        int[] selCols = grid.getSelectedColumns();
        tableModel.fireTableDataChanged();  // triggers refreshHeatRange + markDirty via listener
        grid.clearSelection();
        for (int r : selRows) grid.addRowSelectionInterval(r, r);
        for (int c : selCols) grid.addColumnSelectionInterval(c, c);
    }

    // =========================================================================
    // Cell editing operations
    // =========================================================================

    /**
     * Adds {@code delta} (in physical units) to every selected data cell.
     * Used by the scroll wheel handler.
     */
    private void adjustSelectedCells(double delta) {
        boolean changed = false;
        for (int r : grid.getSelectedRows()) {
            int mr = grid.convertRowIndexToModel(r);
            for (int c : grid.getSelectedColumns()) {
                int mc = grid.convertColumnIndexToModel(c);
                double raw = getRawValue(mr, mc);
                if (Double.isNaN(raw)) continue;
                double newPhys = table.toPhysical(raw) + delta;
                setRawValue(mr, mc, table.toRaw(newPhys));
                changed = true;
            }
        }
        if (changed) {
            fireAndRestoreSelection();
            int modified = countEditableSelectedCells();
            statusLabel.setText(String.format("Adjusted %d selected cell%s.",
                    modified, modified == 1 ? "" : "s"));
        }
    }

    /**
     * Linearly interpolates between the first and last selected value within
     * each selected data row.  All selected cells in between are overwritten.
     */
    private void interpolateSelected() {
        OperationAxis axis = resolveOperationAxis(false);
        if (axis == null) {
            return;
        }

        OperationStats stats = switch (axis) {
            case ROWS -> interpolateRows();
            case COLUMNS -> interpolateColumns();
            default -> new OperationStats();
        };

        if (!stats.changed()) {
            return;
        }

        fireAndRestoreSelection();
        statusLabel.setText(String.format("Interpolated %d span%s across %s.",
                stats.spans, stats.spans == 1 ? "" : "s", axis == OperationAxis.ROWS ? "rows" : "columns"));
    }

    /**
     * Applies a single 3-point equal-weight moving average to the interior of
     * each selected data row. Endpoint cells are held fixed as anchors.
     */
    private void smoothSelected() {
        OperationAxis axis = resolveOperationAxis(false);
        if (axis == null) {
            return;
        }

        OperationStats stats = switch (axis) {
            case ROWS -> smoothRows();
            case COLUMNS -> smoothColumns();
            default -> new OperationStats();
        };

        if (!stats.changed()) {
            return;
        }

        fireAndRestoreSelection();
        statusLabel.setText(String.format("Smoothed %d span%s across %s.",
                stats.spans, stats.spans == 1 ? "" : "s", axis == OperationAxis.ROWS ? "rows" : "columns"));
    }

    private OperationStats interpolateRows() {
        OperationStats stats = new OperationStats();
        int[] modelCols = getSelectedModelColumns();
        int[] modelRows = getSelectedEditableModelRows();
        if (modelCols.length < 2 || modelRows.length == 0) {
            statusLabel.setText("Select at least 2 editable cells in one row to interpolate.");
            return stats;
        }

        int cFirst = modelCols[0];
        int cLast = modelCols[modelCols.length - 1];
        for (int mr : modelRows) {
            if (!tableModel.isCellEditable(mr, cFirst) || !tableModel.isCellEditable(mr, cLast)) {
                continue;
            }
            double physFirst = table.toPhysical(getRawValue(mr, cFirst));
            double physLast = table.toPhysical(getRawValue(mr, cLast));
            for (int mc = cFirst; mc <= cLast; mc++) {
                if (!tableModel.isCellEditable(mr, mc)) {
                    continue;
                }
                double t = (cFirst == cLast) ? 0.0 : (double) (mc - cFirst) / (cLast - cFirst);
                setRawValue(mr, mc, table.toRaw(physFirst + t * (physLast - physFirst)));
                stats.cells++;
            }
            stats.spans++;
        }
        return stats;
    }

    private OperationStats interpolateColumns() {
        OperationStats stats = new OperationStats();
        int[] modelCols = getSelectedModelColumns();
        int[] modelRows = getSelectedEditableModelRows();
        if (modelCols.length == 0 || modelRows.length < 2) {
            statusLabel.setText("Select at least 2 editable cells in one column to interpolate.");
            return stats;
        }

        int rFirst = modelRows[0];
        int rLast = modelRows[modelRows.length - 1];
        for (int mc : modelCols) {
            if (!tableModel.isCellEditable(rFirst, mc) || !tableModel.isCellEditable(rLast, mc)) {
                continue;
            }
            double physFirst = table.toPhysical(getRawValue(rFirst, mc));
            double physLast = table.toPhysical(getRawValue(rLast, mc));
            for (int mr = rFirst; mr <= rLast; mr++) {
                if (!tableModel.isCellEditable(mr, mc)) {
                    continue;
                }
                double t = (rFirst == rLast) ? 0.0 : (double) (mr - rFirst) / (rLast - rFirst);
                setRawValue(mr, mc, table.toRaw(physFirst + t * (physLast - physFirst)));
                stats.cells++;
            }
            stats.spans++;
        }
        return stats;
    }

    private OperationStats smoothRows() {
        OperationStats stats = new OperationStats();
        int[] modelCols = getSelectedModelColumns();
        int[] modelRows = getSelectedEditableModelRows();
        if (modelCols.length < 3 || modelRows.length == 0) {
            statusLabel.setText("Select at least 3 editable cells in one row to smooth.");
            return stats;
        }

        int cFirst = modelCols[0];
        int cLast = modelCols[modelCols.length - 1];
        for (int mr : modelRows) {
            if (!tableModel.isCellEditable(mr, cFirst) || !tableModel.isCellEditable(mr, cLast)) {
                continue;
            }
            double[] phys = new double[cLast - cFirst + 1];
            for (int mc = cFirst; mc <= cLast; mc++) {
                phys[mc - cFirst] = table.toPhysical(getRawValue(mr, mc));
            }
            for (int mc = cFirst + 1; mc < cLast; mc++) {
                double smoothed = (phys[mc - cFirst - 1] + phys[mc - cFirst] + phys[mc - cFirst + 1]) / 3.0;
                setRawValue(mr, mc, table.toRaw(smoothed));
                stats.cells++;
            }
            if (cLast - cFirst >= 2) {
                stats.spans++;
            }
        }
        return stats;
    }

    private OperationStats smoothColumns() {
        OperationStats stats = new OperationStats();
        int[] modelCols = getSelectedModelColumns();
        int[] modelRows = getSelectedEditableModelRows();
        if (modelCols.length == 0 || modelRows.length < 3) {
            statusLabel.setText("Select at least 3 editable cells in one column to smooth.");
            return stats;
        }

        int rFirst = modelRows[0];
        int rLast = modelRows[modelRows.length - 1];
        for (int mc : modelCols) {
            if (!tableModel.isCellEditable(rFirst, mc) || !tableModel.isCellEditable(rLast, mc)) {
                continue;
            }
            double[] phys = new double[rLast - rFirst + 1];
            for (int mr = rFirst; mr <= rLast; mr++) {
                phys[mr - rFirst] = table.toPhysical(getRawValue(mr, mc));
            }
            for (int mr = rFirst + 1; mr < rLast; mr++) {
                double smoothed = (phys[mr - rFirst - 1] + phys[mr - rFirst] + phys[mr - rFirst + 1]) / 3.0;
                setRawValue(mr, mc, table.toRaw(smoothed));
                stats.cells++;
            }
            if (rLast - rFirst >= 2) {
                stats.spans++;
            }
        }
        return stats;
    }

    private OperationAxis resolveOperationAxis(boolean quiet) {
        if (!table.is2D()) {
            return OperationAxis.ROWS;
        }

        OperationAxis requested = axisCombo != null ? (OperationAxis) axisCombo.getSelectedItem() : OperationAxis.AUTO;
        if (requested == null || requested == OperationAxis.AUTO) {
            int[] modelRows = getSelectedEditableModelRows();
            int[] modelCols = getSelectedModelColumns();
            if (modelRows.length > 1 && modelCols.length > 1) {
                if (!quiet) {
                    statusLabel.setText("Choose Rows or Columns for rectangular selections.");
                }
                return null;
            }
            if (modelCols.length > 1) {
                return OperationAxis.ROWS;
            }
            if (modelRows.length > 1) {
                return OperationAxis.COLUMNS;
            }
            if (!quiet) {
                statusLabel.setText("Selection is too small for that operation.");
            }
            return null;
        }
        return requested;
    }

    private int[] getSelectedEditableModelRows() {
        return Arrays.stream(grid.getSelectedRows())
                .map(grid::convertRowIndexToModel)
                .filter(row -> Arrays.stream(grid.getSelectedColumns())
                        .map(grid::convertColumnIndexToModel)
                        .anyMatch(col -> tableModel.isCellEditable(row, col)))
                .sorted()
                .distinct()
                .toArray();
    }

    private int[] getSelectedModelColumns() {
        return Arrays.stream(grid.getSelectedColumns())
                .map(grid::convertColumnIndexToModel)
                .sorted()
                .distinct()
                .toArray();
    }

    private int countEditableSelectedCells() {
        int count = 0;
        for (int row : grid.getSelectedRows()) {
            int modelRow = grid.convertRowIndexToModel(row);
            for (int col : grid.getSelectedColumns()) {
                int modelCol = grid.convertColumnIndexToModel(col);
                if (tableModel.isCellEditable(modelRow, modelCol)) {
                    count++;
                }
            }
        }
        return count;
    }

    // =========================================================================
    // Row/corner header for 2-D tables
    // =========================================================================

    private JTable buildRowHeader(DensoTable2D t2d) {
        DefaultTableModel rhModel = new DefaultTableModel(t2d.getCountY(), 1) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Object getValueAt(int r, int c) {
                return formatAxisValue(t2d.getValuesY()[r]);
            }
        };
        rowHeaderModel = rhModel;

        JTable rh = new JTable(rhModel);
        rh.setBackground(GhidraTheme.tableHeaderBackground());
        rh.setForeground(GhidraTheme.tableHeaderForeground());
        rh.setFont(UI_FONT_BOLD);
        rh.setRowHeight(currentRowHeight);
        rh.setPreferredScrollableViewportSize(new Dimension(currentRowHeaderWidth, 0));
        rh.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            {
                setHorizontalAlignment(SwingConstants.CENTER);
                setBackground(GhidraTheme.tableHeaderBackground());
                setForeground(GhidraTheme.tableHeaderForeground());
                setFont(UI_FONT_BOLD);
            }
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v,
                    boolean s, boolean f, int r, int c) {
                super.getTableCellRendererComponent(t, v, false, false, r, c);
                setOpaque(true);
                setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, GhidraTheme.tableGridColor()));
                return this;
            }
        });
        rh.getColumnModel().getColumn(0).setPreferredWidth(currentRowHeaderWidth);
        rh.setTableHeader(null);
        return rh;
    }

    private JTable buildRowHeader(DensoTable1D t1d) {
        DefaultTableModel rhModel = new DefaultTableModel(2, 1) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Object getValueAt(int r, int c) {
                return r == 0 ? "X Axis" : "Value";
            }
        };
        rowHeaderModel = rhModel;

        JTable rh = new JTable(rhModel);
        rh.setBackground(GhidraTheme.tableHeaderBackground());
        rh.setForeground(GhidraTheme.tableHeaderForeground());
        rh.setFont(UI_FONT_BOLD);
        rh.setRowHeight(currentRowHeight);
        rh.setPreferredScrollableViewportSize(new Dimension(currentRowHeaderWidth, 0));
        rh.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            {
                setHorizontalAlignment(SwingConstants.CENTER);
                setBackground(GhidraTheme.tableHeaderBackground());
                setForeground(GhidraTheme.tableHeaderForeground());
                setFont(UI_FONT_BOLD);
            }
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v,
                    boolean s, boolean f, int r, int c) {
                super.getTableCellRendererComponent(t, v, false, false, r, c);
                setOpaque(true);
                setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, GhidraTheme.tableGridColor()));
                return this;
            }
        });
        rh.getColumnModel().getColumn(0).setPreferredWidth(currentRowHeaderWidth);
        rh.setTableHeader(null);
        return rh;
    }

    private JLabel buildCornerLabel() {
        JLabel l = new JLabel("Y\\X", SwingConstants.CENTER);
        l.setFont(UI_FONT_SMALL.deriveFont(Font.BOLD | Font.ITALIC));
        l.setForeground(GhidraTheme.secondaryForeground());
        l.setBackground(GhidraTheme.surfaceBackground());
        l.setOpaque(true);
        l.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, GhidraTheme.tableGridColor()));
        return l;
    }

    private void autoSizeColumns() {
        int cols = tableModel.getColumnCount();
        FontMetrics metrics = grid.getFontMetrics(grid.getFont());
        for (int c = 0; c < cols; c++) {
            int width = Math.max(currentCellWidth,
                    metrics.stringWidth(grid.getColumnName(c)) + 18);
            grid.getColumnModel().getColumn(c).setPreferredWidth(width);
        }
        if (table.is2D()) {
            float[] xs = table.getValuesX();
            TableColumnModel cm = grid.getColumnModel();
            for (int c = 0; c < xs.length && c < cm.getColumnCount(); c++) {
                cm.getColumn(c).setHeaderValue(formatAxisValue(xs[c]));
            }
        }
        grid.getTableHeader().repaint();
        if (rowHeaderTable != null) {
            rowHeaderTable.setPreferredScrollableViewportSize(new Dimension(currentRowHeaderWidth, 0));
            rowHeaderTable.getColumnModel().getColumn(0).setPreferredWidth(currentRowHeaderWidth);
        }
    }

    private void applyTableDensity() {
        if (grid == null || tableModel == null) {
            return;
        }

        int cols = Math.max(1, tableModel.getColumnCount());
        int dataRows = getDataRowCount();
        TableDensity density = densityCombo != null
                ? (TableDensity) densityCombo.getSelectedItem()
                : TableDensity.AUTO;
        if (density == null) {
            density = TableDensity.AUTO;
        }

        int targetWidth;
        int targetHeight;
        switch (density) {
            case COMPACT -> {
                targetWidth = 52;
                targetHeight = 22;
            }
            case COMFORTABLE -> {
                targetWidth = 84;
                targetHeight = 28;
            }
            case AUTO -> {
                if (cols >= 28 || dataRows >= 20) {
                    targetWidth = 46;
                    targetHeight = 20;
                }
                else if (cols >= 18 || dataRows >= 12) {
                    targetWidth = 58;
                    targetHeight = 23;
                }
                else {
                    targetWidth = 72;
                    targetHeight = 26;
                }
            }
            default -> throw new IllegalStateException("Unhandled density: " + density);
        }

        if (density == TableDensity.AUTO && tableScrollPane != null) {
            int viewportWidth = tableScrollPane.getViewport().getWidth();
            if (viewportWidth > 0) {
                int fitWidth = (viewportWidth - 20) / cols;
                targetWidth = Math.min(targetWidth, Math.max(42, fitWidth));
            }
        }

        currentCellWidth = clamp(targetWidth, 42, 92);
        currentRowHeight = clamp(targetHeight, 20, 30);
        currentRowHeaderWidth = table.is2D() ? clamp(currentCellWidth + 12, 56, 88)
                                             : clamp(currentCellWidth + 16, 60, 92);

        Font tableFont = scaleFont(GhidraTheme.tableFont(),
                currentCellWidth <= 48 ? -2f : currentCellWidth <= 58 ? -1f : 0f);
        Font headerFont = scaleFont(GhidraTheme.tableHeaderFont(),
                currentCellWidth <= 48 ? -2f : currentCellWidth <= 58 ? -1f : 0f);
        Font cornerFont = scaleFont(GhidraTheme.smallFont(),
                currentCellWidth <= 48 ? -1f : 0f).deriveFont(Font.BOLD | Font.ITALIC);

        grid.setFont(tableFont);
        grid.setRowHeight(currentRowHeight);
        grid.getTableHeader().setFont(headerFont);

        if (rowHeaderTable != null) {
            rowHeaderTable.setFont(headerFont);
            rowHeaderTable.setRowHeight(currentRowHeight);
            TableCellRenderer rowRenderer = rowHeaderTable.getDefaultRenderer(Object.class);
            if (rowRenderer instanceof JComponent component) {
                component.setFont(headerFont);
            }
        }
        if (cornerLabel != null) {
            cornerLabel.setFont(cornerFont);
        }

        autoSizeColumns();
        updateTableStats();
        if (grid.isShowing()) {
            grid.revalidate();
            grid.repaint();
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private Font scaleFont(Font base, float delta) {
        return base.deriveFont(Math.max(10f, base.getSize2D() + delta));
    }

    private int getDataRowCount() {
        return table.is2D() ? ((DensoTable2D) table).getCountY() : 1;
    }

    private boolean isLargeTable() {
        return tableModel.getColumnCount() >= 18 || getDataRowCount() >= 12;
    }

    private void updateTableStats() {
        if (tableStatsLabel == null || tableModel == null) {
            return;
        }
        TableDensity density = densityCombo != null
                ? (TableDensity) densityCombo.getSelectedItem()
                : TableDensity.AUTO;
        String densityLabel = density == null ? "Auto" : density.toString();
        tableStatsLabel.setText(String.format(
                "%s  |  %d cols x %d rows  |  %s density",
                table.getDataType().getDisplayName(),
                tableModel.getColumnCount(),
                getDataRowCount(),
                densityLabel));
    }

    private void restoreInspectorLayout() {
        if (splitPane == null || inspectorScrollPane == null || !inspectorScrollPane.isVisible()) {
            return;
        }

        int target = lastDividerLocation > 0
                ? lastDividerLocation
                : Math.max(360, getWidth() - DEFAULT_INSPECTOR_WIDTH);
        splitPane.setDividerLocation(target);
    }

    private void setInspectorVisible(boolean visible) {
        if (inspectorScrollPane == null || splitPane == null) {
            return;
        }
        if (!visible) {
            lastDividerLocation = splitPane.getDividerLocation();
        }
        inspectorScrollPane.setVisible(visible);
        splitPane.setDividerSize(visible ? Math.max(8, UIManager.getInt("SplitPane.dividerSize")) : 0);
        if (inspectorToggle != null && inspectorToggle.isSelected() != visible) {
            inspectorToggle.setSelected(visible);
        }
        splitPane.revalidate();
        if (visible) {
            SwingUtilities.invokeLater(this::restoreInspectorLayout);
        }
        else {
            splitPane.repaint();
        }
    }

    private void refreshHeatRange() {
        double min, max;
        if (table.is2D()) {
            min = max = 0;
            boolean first = true;
            for (int r = 0; r < tableModel.getRowCount(); r++) {
                for (int c = 0; c < tableModel.getColumnCount(); c++) {
                    try {
                        double v = Double.parseDouble(tableModel.getValueAt(r, c).toString());
                        if (first) { min = max = v; first = false; }
                        else { min = Math.min(min, v); max = Math.max(max, v); }
                    } catch (Exception ignored) {}
                }
            }
        } else {
            DoubleSummaryStatistics stats = Arrays.stream(((DensoTable1D) table).getValuesY())
                    .map(table::toPhysical)
                    .summaryStatistics();
            min = stats.getMin();
            max = stats.getMax();
        }
        renderer.setRange(min, max);
        if (grid != null) grid.repaint();
    }

    private void refreshGridFromModel() {
        boolean previousLoading = loading;
        loading = true;
        tableModel.fireTableDataChanged();
        if (rowHeaderModel != null) {
            rowHeaderModel.fireTableDataChanged();
        }
        loading = previousLoading;
        applyTableDensity();
        updateStatus();
    }

    private JTextField makeMacField(String value) {
        JTextField f = new JTextField(value, 10);
        f.setBackground(GhidraTheme.textFieldBackground());
        f.setForeground(GhidraTheme.textFieldForeground());
        f.setCaretColor(GhidraTheme.textFieldCaret());
        f.setFont(GhidraTheme.textFieldFont());
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GhidraTheme.borderColor()),
                BorderFactory.createEmptyBorder(2, 4, 2, 4)));
        return f;
    }

    /**
     * Updates the model's MAC parameters without touching the raw data array.
     * Display refreshes to show the new physical interpretation of unchanged raw values.
     */
    private void commitMacFields() {
        try {
            float newMult = Float.parseFloat(multField.getText().trim());
            float newOff  = Float.parseFloat(offField.getText().trim());
            String validationError = DensoTable.validateMacParameters(newMult, newOff);
            if (validationError != null) {
                statusLabel.setText(validationError);
                multField.setForeground(GhidraTheme.errorForeground());
                offField.setForeground(GhidraTheme.errorForeground());
                return;
            }
            table.setMultiplier(newMult);
            table.setOffset(newOff);
            macExprLabel.setText(table.getMacExpression());
            multField.setForeground(GhidraTheme.textFieldForeground());
            offField.setForeground(GhidraTheme.textFieldForeground());
            tableModel.fireTableDataChanged();
            refreshHeatRange();
            markDirty();
        } catch (NumberFormatException ex) {
            statusLabel.setText("MAC fields must be numeric.");
            multField.setForeground(GhidraTheme.errorForeground());
            offField.setForeground(GhidraTheme.errorForeground());
        }
    }

    // =========================================================================
    // Status
    // =========================================================================

    private void updateStatus() {
        int[] rows = grid.getSelectedRows();
        int[] cols = grid.getSelectedColumns();

        if (rows.length == 0 || cols.length == 0) {
            selectionSummaryLabel.setText("No cells selected.\nPick a region to inspect, edit, or shape.");
            return;
        }

        int count = 0;
        double sum = 0, minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE, lastVal = 0;
        int editableRowCount = getSelectedEditableModelRows().length;
        int editableColCount = getSelectedModelColumns().length;

        for (int r : rows) {
            for (int c : cols) {
                Object val = tableModel.getValueAt(
                        grid.convertRowIndexToModel(r),
                        grid.convertColumnIndexToModel(c));
                try {
                    double v = Double.parseDouble(val.toString());
                    count++; sum += v;
                    minV = Math.min(minV, v); maxV = Math.max(maxV, v);
                    lastVal = v;
                } catch (Exception ignored) {}
            }
        }

        String axisHint = "Curve tools run along the X axis.";
        if (table.is2D()) {
            OperationAxis resolvedAxis = resolveOperationAxis(true);
            if (resolvedAxis == OperationAxis.ROWS) {
                axisHint = "Tools will run across rows.";
            }
            else if (resolvedAxis == OperationAxis.COLUMNS) {
                axisHint = "Tools will run across columns.";
            }
            else {
                axisHint = "Choose Rows or Columns for rectangular selections.";
            }
        }

        if (count == 0) {
            selectionSummaryLabel.setText("Header cells selected.\nThe current selection is read-only.");
        } else if (count == 1) {
            selectionSummaryLabel.setText(String.format(
                    "1 editable cell.\nValue %.6g\n%s",
                    lastVal, axisHint));
        } else {
            selectionSummaryLabel.setText(String.format(
                    "%d editable cells.\n%d rows x %d cols | Min %.4g  Max %.4g  Avg %.4g\n%s",
                    count, editableRowCount, editableColCount, minV, maxV, sum / count, axisHint));
        }
    }

    // =========================================================================
    // Actions
    // =========================================================================

    private void goToAddress() {
        if (tool == null || program == null) return;
        GoToService svc = tool.getService(GoToService.class);
        if (svc == null) return;
        Address addr = program.getAddressFactory()
                .getDefaultAddressSpace()
                .getAddress(table.getHeaderAddress());
        svc.goTo(addr);
    }

    private void createStructure() {
        if (program == null) {
            Msg.showWarn(this, this, "No Program", "No program is currently loaded.");
            return;
        }
        DensoStructureApplier.showDialogAndApply(program, List.of(table), this);
    }

    private void applyChanges() {
        if (program == null) {
            Msg.showWarn(this, this, "No Program", "No program is currently loaded.");
            return;
        }
        if (!dirty) return;
        if (grid.isEditing() && !grid.getCellEditor().stopCellEditing()) {
            statusLabel.setText("Finish editing the current cell before saving.");
            return;
        }
        if (table.isHasMAC()) {
            String validationError = DensoTable.validateMacParameters(
                    table.getMultiplier(), table.getOffset());
            if (validationError != null) {
                Msg.showWarn(this, this, "Invalid MAC", validationError);
                return;
            }
        }

        int tx = program.startTransaction("Edit Denso Table: " + table.getName());
        boolean success = false;
        try {
            Memory mem = program.getMemory();
            if (table.is2D()) write2D((DensoTable2D) table, mem);
            else              write1D((DensoTable1D) table, mem);
            if (table.isHasMAC()) writeMacHeader(table, mem);
            success = true;
            dirty = false;
            saveBtn.setEnabled(false);
            revertBtn.setEnabled(false);
            setTitle(table.getName() + " - GhidraTables");
            statusLabel.setText("Saved to ROM.");
        } catch (Exception ex) {
            Msg.showError(this, this, "Write Error",
                    "Failed to write table: " + ex.getMessage(), ex);
        } finally {
            program.endTransaction(tx, success);
        }
    }

    private void write2D(DensoTable2D t2d, Memory mem) throws Exception {
        AddressSpace space = program.getAddressFactory().getDefaultAddressSpace();
        int elemSize = t2d.getDataType().getValueSize();
        int countX = t2d.getCountX(), countY = t2d.getCountY();
        byte[] raw = new byte[countX * countY * elemSize];
        for (int y = 0; y < countY; y++) {
            for (int x = 0; x < countX; x++) {
                long bits;
                try {
                    bits = t2d.getDataType().doubleToRaw(t2d.getZ(y, x));
                }
                catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException(
                            "Invalid value at row " + (y + 1) + ", column " + (x + 1) +
                            ": " + ex.getMessage(), ex);
                }
                writeBigEndian(raw, (y * countX + x) * elemSize, bits, elemSize);
            }
        }
        mem.setBytes(space.getAddress(t2d.getPtrZ()), raw);
    }

    private void write1D(DensoTable1D t1d, Memory mem) throws Exception {
        AddressSpace space = program.getAddressFactory().getDefaultAddressSpace();
        int elemSize = t1d.getDataType().getValueSize();
        int count    = t1d.getCountX();
        byte[] raw   = new byte[count * elemSize];
        for (int i = 0; i < count; i++) {
            long bits;
            try {
                bits = t1d.getDataType().doubleToRaw(t1d.getValuesY()[i]);
            }
            catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                        "Invalid value at column " + (i + 1) + ": " + ex.getMessage(), ex);
            }
            writeBigEndian(raw, i * elemSize, bits, elemSize);
        }
        mem.setBytes(space.getAddress(t1d.getPtrY()), raw);
    }

    private void writeMacHeader(DensoTable t, Memory mem) throws Exception {
        String validationError = DensoTable.validateMacParameters(
                t.getMultiplier(), t.getOffset());
        if (validationError != null) {
            throw new IllegalArgumentException(validationError);
        }
        AddressSpace space = program.getAddressFactory().getDefaultAddressSpace();
        long macAddr = t.getHeaderAddress() + (t.is2D() ? 20 : 12);
        byte[] buf = new byte[8];
        writeBigEndian(buf, 0, Float.floatToIntBits(t.getMultiplier()) & 0xFFFFFFFFL, 4);
        writeBigEndian(buf, 4, Float.floatToIntBits(t.getOffset())     & 0xFFFFFFFFL, 4);
        mem.setBytes(space.getAddress(macAddr), buf);
    }

    // =========================================================================
    // ROM read helpers
    // =========================================================================

    /**
     * Reads MAC header bytes and data array fresh from ROM into the model.
     * Called at construction and on revert so the display is never stale.
     */
    private void loadFromRom() {
        if (program == null) return;
        try {
            Memory mem = program.getMemory();
            AddressSpace space = program.getAddressFactory().getDefaultAddressSpace();

            // Re-read MAC from header bytes
            if (table.isHasMAC()) {
                long macAddr = table.getHeaderAddress() + (table.is2D() ? 20 : 12);
                byte[] buf = new byte[8];
                mem.getBytes(space.getAddress(macAddr), buf);
                table.setMultiplier(readFloatBE(buf, 0));
                table.setOffset(readFloatBE(buf, 4));
            }

            // Re-read data array
            if (table.is2D()) {
                DensoTable2D t2d = (DensoTable2D) table;
                DensoTableType dtype = t2d.getDataType();
                int es = dtype.getValueSize(), cx = t2d.getCountX(), cy = t2d.getCountY();
                byte[] rawX = new byte[cx * 4];
                mem.getBytes(space.getAddress(t2d.getPtrX()), rawX);
                float[] valuesX = new float[cx];
                for (int i = 0; i < cx; i++) {
                    valuesX[i] = readFloatBE(rawX, i * 4);
                }
                t2d.setValuesX(valuesX);

                byte[] rawY = new byte[cy * 4];
                mem.getBytes(space.getAddress(t2d.getPtrY()), rawY);
                float[] valuesY = new float[cy];
                for (int i = 0; i < cy; i++) {
                    valuesY[i] = readFloatBE(rawY, i * 4);
                }
                t2d.setValuesY(valuesY);

                byte[] raw = new byte[cx * cy * es];
                mem.getBytes(space.getAddress(t2d.getPtrZ()), raw);
                double[][] values = new double[cy][cx];
                for (int y = 0; y < cy; y++) {
                    for (int x = 0; x < cx; x++) {
                        values[y][x] = dtype.rawToDouble(readBigEndian(raw, (y * cx + x) * es, es));
                    }
                }
                t2d.setValuesZ(values);
            } else {
                DensoTable1D t1d = (DensoTable1D) table;
                DensoTableType dtype = t1d.getDataType();
                int es = dtype.getValueSize(), count = t1d.getCountX();
                byte[] rawX = new byte[count * 4];
                mem.getBytes(space.getAddress(t1d.getPtrX()), rawX);
                float[] valuesX = new float[count];
                for (int i = 0; i < count; i++) {
                    valuesX[i] = readFloatBE(rawX, i * 4);
                }
                t1d.setValuesX(valuesX);

                byte[] raw = new byte[count * es];
                mem.getBytes(space.getAddress(t1d.getPtrY()), raw);
                double[] values = new double[count];
                for (int i = 0; i < count; i++) {
                    values[i] = dtype.rawToDouble(readBigEndian(raw, i * es, es));
                }
                t1d.setValuesY(values);
            }
        } catch (Exception ex) {
            Msg.warn(this, "ROM read failed: " + ex.getMessage());
        }
    }

    /** Updates the MAC UI fields to match the current model values. */
    private void syncMacUi() {
        if (!table.isHasMAC() || multField == null) return;
        multField.setText(String.valueOf(table.getMultiplier()));
        offField.setText(String.valueOf(table.getOffset()));
        multField.setForeground(GhidraTheme.textFieldForeground());
        offField.setForeground(GhidraTheme.textFieldForeground());
        macExprLabel.setText(table.getMacExpression());
    }

    private static float readFloatBE(byte[] b, int off) {
        return Float.intBitsToFloat((int) readBigEndian(b, off, 4));
    }

    private static long readBigEndian(byte[] buf, int off, int size) {
        long v = 0;
        for (int i = 0; i < size; i++) v = (v << 8) | (buf[off + i] & 0xFFL);
        return v;
    }

    private static void writeBigEndian(byte[] buf, int off, long value, int size) {
        for (int i = size - 1; i >= 0; i--) {
            buf[off + i] = (byte) (value & 0xFF);
            value >>= 8;
        }
    }

    private void revertChanges() {
        if (!dirty) return;
        if (grid.isEditing()) {
            grid.getCellEditor().cancelCellEditing();
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "Discard all unsaved changes?", "Revert",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;

        loadFromRom();
        syncMacUi();
        dirty = false;
        saveBtn.setEnabled(false);
        revertBtn.setEnabled(false);
        setTitle(table.getName() + " - GhidraTables");
        refreshGridFromModel();
        statusLabel.setText("Reverted from ROM.");
    }

    private void exportCsv() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(table.getName() + ".csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try (PrintWriter pw = new PrintWriter(new FileWriter(fc.getSelectedFile()))) {
            int rows = tableModel.getRowCount(), cols = tableModel.getColumnCount();
            for (int r = 0; r < rows; r++) {
                StringBuilder sb = new StringBuilder();
                for (int c = 0; c < cols; c++) {
                    if (c > 0) sb.append(',');
                    Object v = tableModel.getValueAt(r, c);
                    sb.append(v != null ? v : "");
                }
                pw.println(sb);
            }
            statusLabel.setText("Exported → " + fc.getSelectedFile().getName());
        } catch (IOException ex) {
            Msg.showError(this, this, "Export Error", ex.getMessage(), ex);
        }
    }

    private void handleClose() {
        if (dirty) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "You have unsaved changes.  Close anyway?", "Unsaved Changes",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
        }
        dispose();
    }

    private void markDirty() {
        dirty = true;
        saveBtn.setEnabled(program != null);
        revertBtn.setEnabled(true);
        setTitle(table.getName() + " - GhidraTables [modified]");
    }

    // =========================================================================
    // Table models
    // =========================================================================

    private class Table2DModel extends AbstractTableModel {
        private final DensoTable2D t2d;
        Table2DModel(DensoTable2D t2d) { this.t2d = t2d; }

        @Override public int getRowCount()    { return t2d.getCountY(); }
        @Override public int getColumnCount() { return t2d.getCountX(); }
        @Override public String getColumnName(int col) { return formatAxisValue(t2d.getValuesX()[col]); }

        @Override public Object getValueAt(int row, int col) {
            return formatValue(t2d.toPhysical(t2d.getZ(row, col)));
        }

        @Override public boolean isCellEditable(int row, int col) { return true; }

        @Override public void setValueAt(Object aValue, int row, int col) {
            try {
                double physical = Double.parseDouble(aValue.toString().trim());
                t2d.setZ(row, col, t2d.toRaw(physical));
                fireTableCellUpdated(row, col);
            } catch (NumberFormatException ignored) {}
        }
    }

    private class Table1DModel extends AbstractTableModel {
        private final DensoTable1D t1d;
        Table1DModel(DensoTable1D t1d) { this.t1d = t1d; }

        @Override public int getRowCount()    { return 2; }
        @Override public int getColumnCount() { return t1d.getCountX(); }
        @Override public String getColumnName(int col) { return String.valueOf(col); }

        @Override public Object getValueAt(int row, int col) {
            if (row == 0) return formatAxisValue(t1d.getValuesX()[col]);
            return formatValue(t1d.toPhysical(t1d.getValuesY()[col]));
        }

        @Override public boolean isCellEditable(int row, int col) { return row == 1; }

        @Override public void setValueAt(Object aValue, int row, int col) {
            if (row != 1) return;
            try {
                double physical = Double.parseDouble(aValue.toString().trim());
                t1d.getValuesY()[col] = t1d.toRaw(physical);
                fireTableCellUpdated(row, col);
            } catch (NumberFormatException ignored) {}
        }
    }

    // =========================================================================
    // Formatting / sizing
    // =========================================================================

    private static String formatAxisValue(float v) {
        if (!Float.isFinite(v)) return Float.toString(v);
        float abs = Math.abs(v);
        if (Math.abs(v - Math.round(v)) < 0.0001f) {
            return String.format(Locale.ROOT, "%.0f", v);
        }
        if (abs >= 1000f) {
            return String.format(Locale.ROOT, "%.0f", v);
        }
        if (abs >= 100f) {
            return trimTrailingZeros(String.format(Locale.ROOT, "%.1f", v));
        }
        return trimTrailingZeros(String.format(Locale.ROOT, "%.2f", v));
    }

    private static String formatValue(double v) {
        return trimTrailingZeros(String.format(Locale.ROOT, "%.6f", v));
    }

    private static String trimTrailingZeros(String s) {
        if (!s.contains(".")) return s;
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') end--;
        if (end > 0 && s.charAt(end - 1) == '.') end--;
        return s.substring(0, end);
    }

    private Dimension computePreferredSize() {
        int cols = tableModel.getColumnCount();
        int rows = getDataRowCount() + 1;
        int w    = Math.min(cols * 64 + 340, 1600);
        int h    = Math.min(rows * 24 + 220, 950);
        return new Dimension(Math.max(w, 920), Math.max(h, 500));
    }
}
