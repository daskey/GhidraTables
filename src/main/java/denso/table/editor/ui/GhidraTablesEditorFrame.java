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
 * <h3>Layout</h3>
 * <pre>
 *  ┌── Info bar ────────────────────────────────────────────────────┐
 *  │  Name  │  0xADDR (click → navigate)  │  1D │ 16x1 │ Float │ MAC│
 *  ├── Table (scrollable, heat-map) ────────────────────────────────┤
 *  │  Data values shown as physical (raw × scale + offset).        │
 *  │  Scroll wheel: ±1  Shift: ±10  Ctrl: ±0.1  Ctrl+Shift: ±0.01 │
 *  ├── Edit ops strip ──────────────────────────────────────────────┤
 *  │  [Interpolate]  [Smooth]  [Smooth ×3]                         │
 *  ├── MAC strip (only when table has MAC) ─────────────────────────┤
 *  │  MAC  Scale × [mult]  Offset + [off]  →  expression           │
 *  ├── Action bar ──────────────────────────────────────────────────┤
 *  │  [Save to ROM] [Revert] [Export CSV] [Apply Structure]  status │
 *  └────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Values in the model are stored as <em>raw</em> ROM values.
 * The MAC (scale × offset) is applied on display and inverted on edit.
 * Editing MAC fields updates header parameters without touching raw data.
 */
public class GhidraTablesEditorFrame extends JFrame {

    // ── Colours ───────────────────────────────────────────────────────────────
    private static final Color INFO_BAR_BG   = new Color(28, 36, 48);
    private static final Color ACTION_BAR_BG = new Color(22, 30, 42);
    private static final Color EDIT_OPS_BG   = new Color(24, 33, 46);
    private static final Color INFO_FG       = new Color(190, 210, 230);
    private static final Color DIM_FG        = new Color(120, 150, 190);
    private static final Color ACCENT        = new Color(60, 130, 220);
    private static final Color TABLE_BG      = new Color(18, 24, 36);
    private static final Color GRID_COLOR    = new Color(50, 60, 75);
    private static final Color MAC_BG        = new Color(30, 40, 54);

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

    private JLabel statusLabel;
    private JButton saveBtn;
    private JButton revertBtn;

    private JTextField multField;
    private JTextField offField;
    private JLabel macExprLabel;

    /** True whenever in-memory state differs from the last saved ROM state. */
    private boolean dirty = false;

    /**
     * Set to true during programmatic data loads (initial load, revert) to
     * suppress the dirty-marking side-effect of fireTableDataChanged.
     */
    private boolean loading = false;

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
        setBackground(TABLE_BG);

        add(buildInfoBar(),     BorderLayout.NORTH);
        add(buildCenterPanel(), BorderLayout.CENTER);
        add(buildActionBar(),   BorderLayout.SOUTH);

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
    }

    // =========================================================================
    // Info bar (top)
    // =========================================================================

    private JPanel buildInfoBar() {
        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setBackground(INFO_BAR_BG);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, GRID_COLOR),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        JLabel nameLabel = new JLabel(table.getName());
        nameLabel.setForeground(INFO_FG);
        nameLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));

        JLabel addrLabel = new JLabel(table.getAddressHex());
        addrLabel.setForeground(new Color(110, 180, 255));
        addrLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
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

        JPanel center = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        center.setOpaque(false);
        center.add(addrLabel);

        JPanel chips = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        chips.setOpaque(false);
        chips.add(makeChip(table.is2D() ? "2D" : "1D",           new Color(100, 60, 200)));
        chips.add(makeChip(table.getDimensions(),                 ACCENT));
        chips.add(makeChip(table.getDataType().getDisplayName(), new Color(40, 150, 130)));
        if (table.isHasMAC()) chips.add(makeChip("MAC", new Color(170, 100, 30)));

        bar.add(nameLabel, BorderLayout.WEST);
        bar.add(center,    BorderLayout.CENTER);
        bar.add(chips,     BorderLayout.EAST);
        return bar;
    }

    private JLabel makeChip(String text, Color bg) {
        JLabel chip = new JLabel(" " + text + " ") {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        chip.setFont(new Font("Dialog", Font.BOLD, 11));
        chip.setForeground(Color.WHITE);
        chip.setOpaque(false);
        chip.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        return chip;
    }

    // =========================================================================
    // Action bar (bottom)
    // =========================================================================

    private JPanel buildActionBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBackground(ACTION_BAR_BG);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, GRID_COLOR),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btns.setOpaque(false);

        saveBtn   = makeBtn("Save to ROM", new Color(50, 160, 70),  e -> applyChanges());
        revertBtn = makeBtn("Revert",      new Color(180, 80,  30), e -> revertChanges());
        JButton exportBtn = makeBtn("Export CSV",      new Color(55, 95, 190), e -> exportCsv());
        JButton structBtn = makeBtn("Apply Structure", new Color(80, 50, 140), e -> createStructure());

        saveBtn.setEnabled(false);
        revertBtn.setEnabled(false);

        btns.add(saveBtn);
        btns.add(revertBtn);
        btns.add(Box.createHorizontalStrut(6));
        btns.add(exportBtn);
        btns.add(structBtn);

        statusLabel = new JLabel("Ready");
        statusLabel.setForeground(DIM_FG);
        statusLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

        bar.add(btns,        BorderLayout.WEST);
        bar.add(statusLabel, BorderLayout.EAST);
        return bar;
    }

    private JButton makeBtn(String label, Color accent, ActionListener al) {
        JButton btn = new JButton(label) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color c = !isEnabled()            ? accent.darker().darker()
                        : getModel().isPressed()  ? accent.darker()
                        : getModel().isRollover() ? accent.brighter()
                        : accent;
                g2.setColor(c);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setForeground(Color.WHITE);
        btn.setFont(new Font("Dialog", Font.BOLD, 12));
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addActionListener(al);
        return btn;
    }

    // =========================================================================
    // Center panel (table + edit ops + MAC)
    // =========================================================================

    private JComponent buildCenterPanel() {
        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(TABLE_BG);
        center.add(buildTablePanel(), BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(TABLE_BG);
        south.add(buildEditOpsPanel(), BorderLayout.NORTH);
        if (table.isHasMAC()) south.add(buildMacPanel(), BorderLayout.CENTER);
        center.add(south, BorderLayout.SOUTH);
        return center;
    }

    // =========================================================================
    // Edit operations strip
    // =========================================================================

    private JPanel buildEditOpsPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(EDIT_OPS_BG);
        p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, GRID_COLOR));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        left.setOpaque(false);

        JButton interpBtn = makeBtn("Interpolate", new Color(50, 110, 180), e -> interpolateSelected());
        JButton smoothBtn = makeBtn("Smooth",      new Color(50, 120, 100), e -> smoothSelected(1));
        JButton smooth3Btn = makeBtn("Smooth ×3",  new Color(40,  95,  80), e -> smoothSelected(3));

        interpBtn.setToolTipText("Linear interpolation between first and last selected value in each row");
        smoothBtn.setToolTipText("3-point average smoothing on selected cells (endpoints held fixed)");
        smooth3Btn.setToolTipText("Apply smoothing three times");

        left.add(interpBtn);
        left.add(smoothBtn);
        left.add(smooth3Btn);

        JLabel hint = new JLabel("Scroll wheel: ±1   Shift: ±10   Ctrl: ±0.1   Ctrl+Shift: ±0.01  ");
        hint.setForeground(new Color(80, 100, 130));
        hint.setFont(new Font(Font.MONOSPACED, Font.ITALIC, 10));

        p.add(left, BorderLayout.WEST);
        p.add(hint, BorderLayout.EAST);
        return p;
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

        JScrollPane scroll = new JScrollPane(grid);
        scroll.setBackground(TABLE_BG);
        scroll.getViewport().setBackground(TABLE_BG);
        scroll.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));

        if (table.is2D()) {
            rowHeaderTable = buildRowHeader((DensoTable2D) table);
            scroll.setRowHeaderView(rowHeaderTable);
            scroll.setCorner(JScrollPane.UPPER_LEFT_CORNER, buildCornerLabel());
        }

        return scroll;
    }

    private void configureGrid() {
        grid.setBackground(TABLE_BG);
        grid.setForeground(Color.WHITE);
        grid.setGridColor(GRID_COLOR);
        grid.setRowHeight(24);
        grid.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        grid.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        grid.setCellSelectionEnabled(true);
        grid.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        grid.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

        JTableHeader header = grid.getTableHeader();
        header.setBackground(new Color(38, 50, 66));
        header.setForeground(new Color(180, 200, 220));
        header.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
        header.setReorderingAllowed(false);

        renderer = new HeatMapCellRenderer() {
            @Override
            protected CellRole getRoleFor(int row, int col) {
                if (table.is2D()) {
                    if (row == 0 && col == 0) return CellRole.CORNER;
                    if (row == 0)             return CellRole.X_HEADER;
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
        cellMenu.setBackground(new Color(28, 36, 48));

        JMenuItem miInterp   = new JMenuItem("Interpolate");
        JMenuItem miSmooth   = new JMenuItem("Smooth");
        JMenuItem miSmooth3  = new JMenuItem("Smooth ×3");

        for (JMenuItem mi : new JMenuItem[]{miInterp, miSmooth, miSmooth3}) {
            mi.setBackground(new Color(28, 36, 48));
            mi.setForeground(INFO_FG);
            mi.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        }

        miInterp.addActionListener(e  -> interpolateSelected());
        miSmooth.addActionListener(e  -> smoothSelected(1));
        miSmooth3.addActionListener(e -> smoothSelected(3));

        cellMenu.add(miInterp);
        cellMenu.addSeparator();
        cellMenu.add(miSmooth);
        cellMenu.add(miSmooth3);

        grid.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { if (e.isPopupTrigger()) cellMenu.show(grid, e.getX(), e.getY()); }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) cellMenu.show(grid, e.getX(), e.getY()); }
        });
    }

    // =========================================================================
    // Raw data accessors (model-coordinate row/col)
    // =========================================================================

    /**
     * Returns the raw (pre-MAC) value at the given model cell, or NaN if not a data cell.
     */
    private double getRawValue(int modelRow, int modelCol) {
        if (table.is2D()) {
            if (modelRow < 1) return Double.NaN;
            return ((DensoTable2D) table).getZ(modelRow - 1, modelCol);
        } else {
            if (modelRow != 1) return Double.NaN;
            double[] ys = ((DensoTable1D) table).getValuesY();
            return (modelCol >= 0 && modelCol < ys.length) ? ys[modelCol] : Double.NaN;
        }
    }

    /** Sets the raw value at the given model cell. */
    private void setRawValue(int modelRow, int modelCol, double raw) {
        if (table.is2D()) {
            if (modelRow >= 1) ((DensoTable2D) table).setZ(modelRow - 1, modelCol, raw);
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
        if (changed) fireAndRestoreSelection();
    }

    /**
     * Linearly interpolates between the first and last selected value within
     * each selected data row.  All selected cells in between are overwritten.
     */
    private void interpolateSelected() {
        int[] viewRows = grid.getSelectedRows();
        int[] viewCols = grid.getSelectedColumns();
        if (viewCols.length < 2) return;

        int[] modelCols = Arrays.stream(viewCols)
                .map(c -> grid.convertColumnIndexToModel(c))
                .sorted()
                .toArray();

        boolean changed = false;
        for (int vr : viewRows) {
            int mr = grid.convertRowIndexToModel(vr);
            if (!tableModel.isCellEditable(mr, modelCols[0])) continue;

            int cFirst = modelCols[0];
            int cLast  = modelCols[modelCols.length - 1];
            double physFirst = table.toPhysical(getRawValue(mr, cFirst));
            double physLast  = table.toPhysical(getRawValue(mr, cLast));

            for (int mc : modelCols) {
                double t = (cFirst == cLast) ? 0
                        : (double)(mc - cFirst) / (cLast - cFirst);
                double interp = physFirst + t * (physLast - physFirst);
                setRawValue(mr, mc, table.toRaw(interp));
            }
            changed = true;
        }
        if (changed) fireAndRestoreSelection();
    }

    /**
     * Applies {@code passes} iterations of a 3-point equal-weight moving average
     * to the interior of each selected data row.  Endpoint cells are held fixed
     * as anchors so the overall shape is preserved.
     */
    private void smoothSelected(int passes) {
        int[] viewRows = grid.getSelectedRows();
        int[] viewCols = grid.getSelectedColumns();
        if (viewCols.length < 3) {
            statusLabel.setText("Select at least 3 cells to smooth.");
            return;
        }

        int[] modelCols = Arrays.stream(viewCols)
                .map(c -> grid.convertColumnIndexToModel(c))
                .sorted()
                .toArray();

        boolean changed = false;
        for (int vr : viewRows) {
            int mr = grid.convertRowIndexToModel(vr);
            if (!tableModel.isCellEditable(mr, modelCols[0])) continue;

            for (int pass = 0; pass < passes; pass++) {
                // Snapshot physical values for this row
                double[] phys = new double[modelCols.length];
                for (int ci = 0; ci < modelCols.length; ci++) {
                    phys[ci] = table.toPhysical(getRawValue(mr, modelCols[ci]));
                }
                // 3-point average on interior cells; endpoints stay fixed
                for (int ci = 1; ci < modelCols.length - 1; ci++) {
                    double smoothed = (phys[ci - 1] + phys[ci] + phys[ci + 1]) / 3.0;
                    setRawValue(mr, modelCols[ci], table.toRaw(smoothed));
                }
            }
            changed = true;
        }
        if (changed) fireAndRestoreSelection();
    }

    // =========================================================================
    // Row/corner header for 2-D tables
    // =========================================================================

    private JTable buildRowHeader(DensoTable2D t2d) {
        DefaultTableModel rhModel = new DefaultTableModel(t2d.getCountY() + 1, 1) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Object getValueAt(int r, int c) {
                if (r == 0) return "";
                return formatFloat(t2d.getValuesY()[r - 1]);
            }
        };
        rowHeaderModel = rhModel;

        JTable rh = new JTable(rhModel);
        rh.setBackground(new Color(38, 50, 66));
        rh.setForeground(new Color(180, 200, 220));
        rh.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        rh.setRowHeight(grid.getRowHeight());
        rh.setPreferredScrollableViewportSize(new Dimension(70, 0));
        rh.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            {
                setHorizontalAlignment(SwingConstants.CENTER);
                setBackground(new Color(38, 50, 66));
                setForeground(new Color(180, 200, 220));
                setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
            }
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v,
                    boolean s, boolean f, int r, int c) {
                super.getTableCellRendererComponent(t, v, false, false, r, c);
                setOpaque(true);
                setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, GRID_COLOR));
                return this;
            }
        });
        rh.getColumnModel().getColumn(0).setPreferredWidth(70);
        rh.setTableHeader(null);
        return rh;
    }

    private JLabel buildCornerLabel() {
        JLabel l = new JLabel("Y\\X", SwingConstants.CENTER);
        l.setFont(new Font(Font.MONOSPACED, Font.BOLD | Font.ITALIC, 11));
        l.setForeground(new Color(120, 150, 180));
        l.setBackground(new Color(25, 35, 48));
        l.setOpaque(true);
        l.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, GRID_COLOR));
        return l;
    }

    private void autoSizeColumns() {
        int cols = tableModel.getColumnCount();
        for (int c = 0; c < cols; c++) {
            grid.getColumnModel().getColumn(c).setPreferredWidth(72);
        }
        if (table.is2D()) {
            float[] xs = table.getValuesX();
            TableColumnModel cm = grid.getColumnModel();
            for (int c = 0; c < xs.length && c < cm.getColumnCount(); c++) {
                cm.getColumn(c).setHeaderValue(formatFloat(xs[c]));
            }
        }
        grid.getTableHeader().repaint();
    }

    private void refreshHeatRange() {
        double min, max;
        if (table.is2D()) {
            min = max = 0;
            boolean first = true;
            for (int r = 1; r < tableModel.getRowCount(); r++) {
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
        autoSizeColumns();
        updateStatus();
    }

    // =========================================================================
    // MAC panel
    // =========================================================================

    private JPanel buildMacPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        panel.setBackground(MAC_BG);
        panel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, GRID_COLOR));

        JLabel lbl      = new JLabel("MAC");
        JLabel scaleLbl = new JLabel("Scale ×");
        JLabel offLbl   = new JLabel("Offset +");

        lbl.setForeground(new Color(200, 160, 60));
        lbl.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
        scaleLbl.setForeground(DIM_FG);
        scaleLbl.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        offLbl.setForeground(DIM_FG);
        offLbl.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

        multField    = makeMacField(String.valueOf(table.getMultiplier()));
        offField     = makeMacField(String.valueOf(table.getOffset()));
        macExprLabel = new JLabel(table.getMacExpression());
        macExprLabel.setForeground(new Color(130, 190, 130));
        macExprLabel.setFont(new Font(Font.MONOSPACED, Font.ITALIC, 11));

        ActionListener commit = e -> commitMacFields();
        multField.addActionListener(commit);
        offField.addActionListener(commit);
        multField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { commitMacFields(); }
        });
        offField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { commitMacFields(); }
        });

        panel.add(lbl);
        panel.add(scaleLbl);
        panel.add(multField);
        panel.add(offLbl);
        panel.add(offField);
        panel.add(Box.createHorizontalStrut(6));
        panel.add(macExprLabel);
        return panel;
    }

    private JTextField makeMacField(String value) {
        JTextField f = new JTextField(value, 10);
        f.setBackground(new Color(25, 34, 46));
        f.setForeground(new Color(200, 220, 180));
        f.setCaretColor(Color.WHITE);
        f.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(60, 80, 100)),
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
                multField.setForeground(new Color(220, 80, 80));
                offField.setForeground(new Color(220, 80, 80));
                return;
            }
            table.setMultiplier(newMult);
            table.setOffset(newOff);
            macExprLabel.setText(table.getMacExpression());
            multField.setForeground(new Color(200, 220, 180));
            offField.setForeground(new Color(200, 220, 180));
            tableModel.fireTableDataChanged();
            refreshHeatRange();
            markDirty();
        } catch (NumberFormatException ex) {
            statusLabel.setText("MAC fields must be numeric.");
            multField.setForeground(new Color(220, 80, 80));
            offField.setForeground(new Color(220, 80, 80));
        }
    }

    // =========================================================================
    // Status
    // =========================================================================

    private void updateStatus() {
        int[] rows = grid.getSelectedRows();
        int[] cols = grid.getSelectedColumns();

        if (rows.length == 0 || cols.length == 0) {
            statusLabel.setText("No selection");
            return;
        }

        int count = 0;
        double sum = 0, minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE, lastVal = 0;

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

        if (count == 0) {
            statusLabel.setText("Header cells (read-only)");
        } else if (count == 1) {
            statusLabel.setText(String.format("Val: %.6g", lastVal));
        } else {
            statusLabel.setText(String.format(
                    "%d cells  Min: %.4g  Max: %.4g  Avg: %.4g",
                    count, minV, maxV, sum / count));
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
        multField.setForeground(new Color(200, 220, 180));
        offField.setForeground(new Color(200, 220, 180));
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

        @Override public int getRowCount()    { return t2d.getCountY() + 1; }
        @Override public int getColumnCount() { return t2d.getCountX(); }
        @Override public String getColumnName(int col) { return formatFloat(t2d.getValuesX()[col]); }

        @Override public Object getValueAt(int row, int col) {
            if (row == 0) return formatFloat(t2d.getValuesX()[col]);
            return formatValue(t2d.toPhysical(t2d.getZ(row - 1, col)));
        }

        @Override public boolean isCellEditable(int row, int col) { return row > 0; }

        @Override public void setValueAt(Object aValue, int row, int col) {
            if (row == 0) return;
            try {
                double physical = Double.parseDouble(aValue.toString().trim());
                t2d.setZ(row - 1, col, t2d.toRaw(physical));
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
            if (row == 0) return formatFloat(t1d.getValuesX()[col]);
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

    private static String formatFloat(float f)  { return String.format("%.4g", f); }
    private static String formatValue(double v) { return String.format("%.6g", v); }

    private Dimension computePreferredSize() {
        int cols = tableModel.getColumnCount();
        int rows = tableModel.getRowCount();
        int w    = Math.min(cols * 76 + 120, 1400);
        int h    = Math.min(rows * 26 + 200,  900);
        return new Dimension(Math.max(w, 520), Math.max(h, 340));
    }
}
