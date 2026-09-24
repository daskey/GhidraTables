/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor.ui;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntToDoubleFunction;
import java.util.function.Supplier;
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
    private static final int DEFAULT_INSPECTOR_WIDTH = 264;

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

    /** Snapshot of the raw table payload taken before an edit. */
    private record UndoSnapshot(String description, double[] values1D, double[][] values2D) {}

    private static final int MAX_UNDO_LEVELS = 50;

    /** Consecutive wheel ticks closer together than this share one undo entry. */
    private static final long WHEEL_UNDO_COALESCE_MILLIS = 1500;

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

    // Inspector selection-card action buttons
    private JButton inspInterpolateBtn;
    private JButton inspSmoothBtn;

    // Collapsible overview
    private JPanel overviewContent;
    private JButton overviewToggleBtn;

    // Undo support (bounded, newest first). Covers raw payload edits, not MAC fields.
    private final Deque<UndoSnapshot> undoStack = new ArrayDeque<>();
    /** True while mouse-wheel adjustments are accumulating into the newest undo entry. */
    private boolean wheelUndoOpen = false;
    private long lastWheelAdjustMillis = 0;

    /** Notified after a successful save so the table list can refresh. */
    private Consumer<DensoTable> saveListener = t -> {};

    // Zoom override for density
    private boolean userZoomLocked = false;

    /** True whenever in-memory state differs from the last saved ROM state. */
    private boolean dirty = false;
    /** True when raw table payload bytes have been modified in the editor. */
    private boolean dataDirty = false;
    /** True when only the MAC header fields have been modified in the editor. */
    private boolean macDirty = false;

    /**
     * Set to true during programmatic data loads (initial load, revert) to
     * suppress the dirty-marking side-effect of fireTableDataChanged.
     */
    private boolean loading = false;
    private int currentCellWidth = 76;
    private int currentRowHeight = 28;
    private int currentRowHeaderWidth = 76;
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

        add(buildUnifiedHeader(), BorderLayout.NORTH);
        add(buildCenterPanel(),   BorderLayout.CENTER);

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

        setMinimumSize(new Dimension(520, 320));
        setSize(computePreferredSize());

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
    // Public API (used by the table list to track open editors)
    // =========================================================================

    /** Returns true when there are edits that have not been saved to the program. */
    public boolean hasUnsavedChanges() {
        return dirty;
    }

    /** Sets a callback invoked with the edited table after each successful save. */
    public void setSaveListener(Consumer<DensoTable> listener) {
        saveListener = listener != null ? listener : t -> {};
    }

    /** Closes the window immediately, discarding unsaved changes without prompting. */
    public void closeWithoutPrompt() {
        if (grid != null && grid.isEditing()) {
            grid.getCellEditor().cancelCellEditing();
        }
        dispose();
    }

    // =========================================================================
    // Unified header (replaces info bar + toolbar + action bar)
    // =========================================================================

    private JPanel buildUnifiedHeader() {
        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setBackground(GhidraTheme.surfaceBackground());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, GhidraTheme.subtleBorderColor()),
                BorderFactory.createEmptyBorder(7, 12, 7, 12)));

        // ── Left: table name + info chips ───────────────────────────────
        JPanel leftGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        leftGroup.setOpaque(false);

        JLabel nameLabel = new JLabel(table.getName());
        nameLabel.setForeground(GhidraTheme.primaryForeground());
        nameLabel.setFont(UI_FONT_TITLE);
        nameLabel.setToolTipText(table.getAddressHex() + " \u2014 click to navigate");
        nameLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        nameLabel.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) goToAddress();
            }
        });

        leftGroup.add(nameLabel);
        leftGroup.add(Box.createHorizontalStrut(6));
        leftGroup.add(makeChip(table.is2D() ? "2D" : "1D", GhidraTheme.secondaryForeground()));
        leftGroup.add(makeChip(table.getDimensions(), GhidraTheme.tableSelectionBackground()));
        leftGroup.add(makeChip(table.getDataType().getDisplayName(), GhidraTheme.linkForeground()));
        if (table.isHasMAC()) leftGroup.add(makeChip("MAC", GhidraTheme.linkForeground()));

        bar.add(leftGroup, BorderLayout.WEST);

        // ── Right: operations + view controls + save/revert ─────────────
        JPanel rightGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightGroup.setOpaque(false);

        // -- Editing operations --
        JButton interpBtn = makeToolbarBtn("Interpolate", "Interpolate selected cells", e -> interpolateSelected());
        JButton smoothBtn = makeToolbarBtn("Smooth", "Smooth selected cells", e -> smoothSelected());
        JButton undoBtn = makeToolbarBtn("Undo", "Undo last edit", e -> undoLastOperation());

        rightGroup.add(interpBtn);
        rightGroup.add(smoothBtn);
        rightGroup.add(undoBtn);
        rightGroup.add(makeSeparator());

        // -- More actions dropdown (Export CSV, Apply Structure) --
        JButton moreBtn = makeToolbarBtn("Actions", "More actions", null);
        JPopupMenu moreMenu = new JPopupMenu();
        JMenuItem miExport = new JMenuItem("Export CSV");
        miExport.setFont(GhidraTheme.labelFont());
        miExport.addActionListener(e -> exportCsv());
        JMenuItem miStructure = new JMenuItem("Apply Structure");
        miStructure.setFont(GhidraTheme.labelFont());
        miStructure.addActionListener(e -> createStructure());
        moreMenu.add(miExport);
        moreMenu.add(miStructure);
        moreBtn.addActionListener(e -> moreMenu.show(moreBtn, 0, moreBtn.getHeight()));

        rightGroup.add(moreBtn);
        rightGroup.add(makeSeparator());

        // -- View controls --
        densityCombo = new JComboBox<>(TableDensity.values());
        densityCombo.setSelectedItem(TableDensity.AUTO);
        densityCombo.setFont(UI_FONT_SMALL);
        densityCombo.setToolTipText("Cell density");
        densityCombo.addActionListener(e -> {
            userZoomLocked = false;
            applyTableDensity();
        });

        axisCombo = new JComboBox<>(table.is2D()
                ? OperationAxis.values()
                : new OperationAxis[]{OperationAxis.ROWS});
        axisCombo.setSelectedItem(table.is2D() ? OperationAxis.AUTO : OperationAxis.ROWS);
        axisCombo.setFont(UI_FONT_SMALL);
        axisCombo.setToolTipText("Operation axis");

        inspectorToggle = makeToolbarToggle("Inspector",
                "Show or hide the inspector panel", true);
        inspectorToggle.addActionListener(e -> setInspectorVisible(inspectorToggle.isSelected()));

        rightGroup.add(makeToolbarControl("Density", densityCombo));
        if (table.is2D()) {
            rightGroup.add(makeToolbarControl("Axis", axisCombo));
        }
        rightGroup.add(inspectorToggle);
        rightGroup.add(makeSeparator());

        // -- Save/Revert (small icon buttons) --
        saveBtn = makeToolbarBtn("Save", "Save to ROM", e -> applyChanges());
        revertBtn = makeToolbarBtn("Revert", "Revert changes", e -> revertChanges());
        saveBtn.setEnabled(false);
        revertBtn.setEnabled(false);

        rightGroup.add(saveBtn);
        rightGroup.add(revertBtn);

        bar.add(rightGroup, BorderLayout.EAST);
        return bar;
    }

    private JLabel makeChip(String text, Color accent) {
        Color bg = GhidraTheme.mix(GhidraTheme.cardBackground(), accent, 0.14f);
        Color border = GhidraTheme.mix(GhidraTheme.borderColor(), accent, 0.28f);
        JLabel chip = new JLabel(" " + text + " ") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.setColor(border);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        chip.setFont(UI_FONT_SMALL.deriveFont(Font.BOLD));
        chip.setForeground(GhidraTheme.primaryForeground());
        chip.setOpaque(false);
        chip.setBorder(BorderFactory.createEmptyBorder(3, 7, 3, 7));
        return chip;
    }

    private static JSeparator makeSeparator() {
        JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 20));
        return sep;
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

    private JButton makeSmallBtn(String text, String tooltip, ActionListener al) {
        JButton btn = new JButton(text);
        btn.setFont(UI_FONT_BOLD);
        btn.setFocusPainted(false);
        btn.setMargin(new Insets(3, 8, 3, 8));
        btn.setToolTipText(tooltip);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBorderPainted(true);
        btn.addMouseListener(new MouseAdapter() {
            Color normalBg;
            @Override public void mouseEntered(MouseEvent e) {
                normalBg = btn.getBackground();
                btn.setBackground(GhidraTheme.cardHoverBackground());
            }
            @Override public void mouseExited(MouseEvent e) {
                if (normalBg != null) btn.setBackground(normalBg);
            }
        });
        if (al != null) btn.addActionListener(al);
        return btn;
    }

    private JButton makeToolbarBtn(String text, String tooltip, ActionListener al) {
        JButton btn = new JButton(text);
        btn.setFont(UI_FONT_BOLD);
        btn.setFocusPainted(false);
        btn.setMargin(new Insets(5, 12, 5, 12));
        btn.setToolTipText(tooltip);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (al != null) {
            btn.addActionListener(al);
        }
        return btn;
    }

    private JToggleButton makeToolbarToggle(String text, String tooltip, boolean selected) {
        JToggleButton btn = new JToggleButton(text, selected);
        btn.setFont(UI_FONT_BOLD);
        btn.setFocusPainted(false);
        btn.setMargin(new Insets(5, 12, 5, 12));
        btn.setToolTipText(tooltip);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private JComponent makeToolbarControl(String label, JComponent control) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setOpaque(false);

        JLabel title = new JLabel(label);
        title.setFont(UI_FONT_SMALL);
        title.setForeground(GhidraTheme.secondaryForeground());

        panel.add(title);
        panel.add(control);
        return panel;
    }

    // =========================================================================
    // Center panel (table + edit ops + MAC)
    // =========================================================================

    private JComponent buildCenterPanel() {
        // Use a layered approach: table panel with status overlay in bottom-left
        JPanel workspace = new JPanel(new BorderLayout());
        workspace.setBackground(GhidraTheme.panelBackground());

        JScrollPane tablePanel = buildTablePanel();

        // Status label as an overlay in the bottom-left of the table scroll pane
        statusLabel = new JLabel("Ready to edit");
        statusLabel.setForeground(GhidraTheme.secondaryForeground());
        statusLabel.setFont(UI_FONT_SMALL);
        statusLabel.setOpaque(true);
        statusLabel.setBackground(GhidraTheme.mix(GhidraTheme.surfaceBackground(), GhidraTheme.panelBackground(), 0.5f));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));

        tableStatsLabel = new JLabel();
        tableStatsLabel.setFont(UI_FONT_SMALL);
        tableStatsLabel.setForeground(GhidraTheme.secondaryForeground());

        // Use an overlay panel at the bottom of the table area
        JPanel tableWithOverlay = new JPanel(new BorderLayout());
        tableWithOverlay.add(tablePanel, BorderLayout.CENTER);

        JPanel overlayBar = new JPanel(new BorderLayout());
        overlayBar.setOpaque(false);
        overlayBar.setBorder(BorderFactory.createEmptyBorder(0, 8, 2, 8));
        overlayBar.add(statusLabel, BorderLayout.WEST);
        overlayBar.add(tableStatsLabel, BorderLayout.EAST);
        tableWithOverlay.add(overlayBar, BorderLayout.SOUTH);

        workspace.add(tableWithOverlay, BorderLayout.CENTER);

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

    // =========================================================================
    // Inspector
    // =========================================================================

    private JComponent buildInspectorPanel() {
        JPanel sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBackground(GhidraTheme.surfaceBackground());
        sidebar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, GhidraTheme.subtleBorderColor()),
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
        overviewContent = new JPanel();
        overviewContent.setOpaque(false);
        overviewContent.setLayout(new BoxLayout(overviewContent, BoxLayout.Y_AXIS));
        overviewContent.add(buildMetaRow("Dimensions", table.getDimensions()));
        overviewContent.add(buildMetaRow("Data Type", table.getDataType().getDisplayName()));
        overviewContent.add(buildMetaRow("X Points", Integer.toString(table.getCountX())));
        if (table.is2D()) {
            overviewContent.add(buildMetaRow("Y Points",
                    Integer.toString(((DensoTable2D) table).getCountY())));
        }
        overviewContent.add(buildMetaRow("Header", table.getAddressHex()));
        overviewContent.add(buildMetaRow("MAC", table.isHasMAC() ? table.getMacExpression() : "None"));

        // Start collapsed — show one-line summary in header instead
        overviewContent.setVisible(false);
        return buildCollapsibleCard("Overview", makeOverviewSummary(), overviewContent);
    }

    private String makeOverviewSummary() {
        String dim = table.is2D() ? "2D" : "1D";
        String mac = table.isHasMAC() ? " \u00B7 MAC" : "";
        return dim + " \u00B7 " + table.getDimensions() + " \u00B7 "
                + table.getDataType().getDisplayName() + mac;
    }

    private JComponent buildCollapsibleCard(String title, String summary, JPanel content) {
        JPanel card = new JPanel(new BorderLayout(0, 6)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.setColor(GhidraTheme.subtleBorderColor());
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g2.dispose();
            }
        };
        card.setAlignmentX(0f);
        card.setBackground(GhidraTheme.cardBackground());
        card.setOpaque(false);
        card.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setOpaque(false);
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setForeground(GhidraTheme.primaryForeground());
        titleLabel.setFont(UI_FONT_BOLD);

        JLabel summaryLabel = new JLabel(summary);
        summaryLabel.setForeground(GhidraTheme.secondaryForeground());
        summaryLabel.setFont(UI_FONT_SMALL);

        overviewToggleBtn = new JButton(content.isVisible() ? "\u25BC" : "\u25B6");
        overviewToggleBtn.setFont(UI_FONT_SMALL);
        overviewToggleBtn.setBorderPainted(false);
        overviewToggleBtn.setContentAreaFilled(false);
        overviewToggleBtn.setFocusPainted(false);
        overviewToggleBtn.setMargin(new Insets(0, 0, 0, 0));

        header.add(overviewToggleBtn, BorderLayout.WEST);
        header.add(titleLabel, BorderLayout.CENTER);
        header.add(summaryLabel, BorderLayout.EAST);

        header.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                boolean show = !content.isVisible();
                content.setVisible(show);
                overviewToggleBtn.setText(show ? "\u25BC" : "\u25B6");
                summaryLabel.setVisible(!show);
                card.revalidate();
            }
        });

        card.add(header, BorderLayout.NORTH);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private JComponent buildSelectionCard() {
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        selectionSummaryLabel = makeWrappedTextArea(
                "", GhidraTheme.labelFont(), GhidraTheme.primaryForeground());
        selectionSummaryLabel.setAlignmentX(0f);

        // Action buttons directly in the selection card
        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actionRow.setOpaque(false);
        actionRow.setAlignmentX(0f);

        inspInterpolateBtn = makeBtn("Interpolate", e -> interpolateSelected());
        inspInterpolateBtn.setFont(UI_FONT_SMALL);
        inspInterpolateBtn.setMargin(new Insets(2, 8, 2, 8));
        inspSmoothBtn = makeBtn("Smooth", e -> smoothSelected());
        inspSmoothBtn.setFont(UI_FONT_SMALL);
        inspSmoothBtn.setMargin(new Insets(2, 8, 2, 8));

        actionRow.add(inspInterpolateBtn);
        actionRow.add(inspSmoothBtn);

        content.add(selectionSummaryLabel);
        content.add(Box.createVerticalStrut(6));
        content.add(actionRow);

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
        JPanel card = new JPanel(new BorderLayout(0, 10)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.setColor(GhidraTheme.subtleBorderColor());
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g2.dispose();
            }
        };
        card.setAlignmentX(0f);
        card.setBackground(GhidraTheme.cardBackground());
        card.setOpaque(false);
        card.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setForeground(GhidraTheme.secondaryForeground());
        titleLabel.setFont(UI_FONT_SMALL.deriveFont(Font.BOLD));

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
        tableScrollPane.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GhidraTheme.subtleBorderColor()),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)));
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
        grid.setShowVerticalLines(true);
        grid.setShowHorizontalLines(true);
        grid.setIntercellSpacing(new Dimension(1, 1));
        grid.setRowHeight(currentRowHeight);
        grid.setFont(UI_FONT);
        grid.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        grid.setCellSelectionEnabled(true);
        grid.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        grid.setFillsViewportHeight(true);
        grid.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

        JTableHeader header = grid.getTableHeader();
        header.setBackground(GhidraTheme.tableHeaderBackground());
        header.setForeground(GhidraTheme.tableHeaderForeground());
        header.setFont(UI_FONT_BOLD);
        header.setReorderingAllowed(false);
        header.setDefaultRenderer(new DefaultTableCellRenderer() {
            {
                setHorizontalAlignment(SwingConstants.CENTER);
            }
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v,
                    boolean sel, boolean focus, int r, int c) {
                super.getTableCellRendererComponent(t, v, false, false, r, c);
                setOpaque(true);
                setFont(UI_FONT_BOLD);
                setBackground(GhidraTheme.tableHeaderBackground());
                setForeground(GhidraTheme.tableHeaderForeground());
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 1, GhidraTheme.subtleBorderColor()),
                        BorderFactory.createEmptyBorder(3, 4, 3, 4)));
                return this;
            }
        });

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
        editor.setValidator(this::validateEditText);
        editor.setBeforeApply(() -> saveUndoState("edit"));
        grid.setDefaultEditor(Object.class, editor);

        autoSizeColumns();

        grid.getSelectionModel().addListSelectionListener(e -> {
            wheelUndoOpen = false;
            if (!e.getValueIsAdjusting()) updateStatus();
        });
        grid.getColumnModel().getSelectionModel().addListSelectionListener(e -> {
            wheelUndoOpen = false;
            if (!e.getValueIsAdjusting()) updateStatus();
        });

        tableModel.addTableModelListener(e -> {
            refreshHeatRange();
            if (!loading) markDataDirty();
            updateTableStats();
        });

        // ── Scroll wheel: adjust selected data cells ──────────────────────────
        grid.addMouseWheelListener(e -> {
            // Only adjust when the pointer is over a selected editable cell, so
            // scrolling elsewhere in the grid can't silently change values.
            int viewRow = grid.rowAtPoint(e.getPoint());
            int viewCol = grid.columnAtPoint(e.getPoint());
            boolean overSelectedDataCell = viewRow >= 0 && viewCol >= 0
                    && grid.isCellSelected(viewRow, viewCol)
                    && tableModel.isCellEditable(grid.convertRowIndexToModel(viewRow),
                            grid.convertColumnIndexToModel(viewCol));
            if (!overSelectedDataCell || grid.isEditing()) {
                // A component with its own wheel listener never passes wheel
                // events up the hierarchy, so hand them to the scroll pane.
                Container scroller = SwingUtilities.getAncestorOfClass(JScrollPane.class, grid);
                if (scroller != null) {
                    scroller.dispatchEvent(SwingUtilities.convertMouseEvent(grid, e, scroller));
                }
                return;
            }

            double step;
            String stepLabel;
            if (e.isShiftDown() && e.isControlDown()) { step = 0.01; stepLabel = "\u00D70.01"; }
            else if (e.isShiftDown())                  { step = 10.0; stepLabel = "\u00D710"; }
            else if (e.isControlDown())                { step = 0.1;  stepLabel = "\u00D70.1"; }
            else                                       { step = 1.0;  stepLabel = "\u00D71"; }

            e.consume();

            // Consecutive ticks on the same selection share one undo entry.
            boolean openedUndo = false;
            if (!wheelUndoOpen || e.getWhen() - lastWheelAdjustMillis > WHEEL_UNDO_COALESCE_MILLIS) {
                saveUndoState("scroll-adjust");
                wheelUndoOpen = true;
                openedUndo = true;
            }
            lastWheelAdjustMillis = e.getWhen();

            int changed = adjustSelectedCells(-e.getWheelRotation() * step);
            if (changed == 0 && openedUndo) {
                discardLastUndoState();
            }
            statusLabel.setText(changed == 0
                    ? "Selected cells are already at the limit of the " +
                            table.getDataType().getDisplayName() + " range."
                    : String.format("Scroll %s \u00B7 Shift=\u00D710 \u00B7 Ctrl=fine \u00B7 Shift+Ctrl=\u00D70.01",
                            stepLabel));
        });

        // ── Keyboard shortcuts (take precedence over JTable's defaults) ─────
        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        bindGridKey(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask), "ghidratables.undo",
                this::undoLastOperation);
        bindGridKey(KeyStroke.getKeyStroke(KeyEvent.VK_C, menuMask), "ghidratables.copy",
                this::copySelectedCells);
        bindGridKey(KeyStroke.getKeyStroke(KeyEvent.VK_V, menuMask), "ghidratables.paste",
                this::pasteIntoCells);

        // ── Ctrl+/- zoom override ──────────────────────────────────────────
        grid.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.isControlDown() && (e.getKeyCode() == KeyEvent.VK_EQUALS || e.getKeyCode() == KeyEvent.VK_PLUS)) {
                    userZoomLocked = true;
                    currentCellWidth = clamp(currentCellWidth + 4, 42, 92);
                    applyTableDensity();
                    e.consume();
                } else if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_MINUS) {
                    userZoomLocked = true;
                    currentCellWidth = clamp(currentCellWidth - 4, 42, 92);
                    applyTableDensity();
                    e.consume();
                } else if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_0) {
                    userZoomLocked = false;
                    applyTableDensity();
                    e.consume();
                }
            }
        });

        // ── Right-click context menu ──────────────────────────────────────────
        JPopupMenu cellMenu = new JPopupMenu();

        JMenuItem miCopy       = new JMenuItem("Copy");
        JMenuItem miPaste      = new JMenuItem("Paste");
        JMenuItem miInterp     = new JMenuItem("Interpolate");
        JMenuItem miSmooth     = new JMenuItem("Smooth");
        JMenuItem miSetValue   = new JMenuItem("Set Value\u2026");
        JMenuItem miSetZero    = new JMenuItem("Set to 0");
        JMenuItem miFillRight  = new JMenuItem("Fill Right");
        JMenuItem miFillDown   = new JMenuItem("Fill Down");
        JMenuItem miUndoCtx    = new JMenuItem("Undo");

        for (JMenuItem mi : new JMenuItem[]{miCopy, miPaste, miInterp, miSmooth,
                miSetValue, miSetZero, miFillRight, miFillDown, miUndoCtx}) {
            mi.setFont(GhidraTheme.labelFont());
        }

        miCopy.addActionListener(e -> copySelectedCells());
        miPaste.addActionListener(e -> pasteIntoCells());
        miInterp.addActionListener(e -> interpolateSelected());
        miSmooth.addActionListener(e -> smoothSelected());
        miSetValue.addActionListener(e -> setValueDialog());
        miSetZero.addActionListener(e -> fillSelectedCells(0, "set-zero"));
        miFillRight.addActionListener(e -> fillRight());
        miFillDown.addActionListener(e -> fillDown());
        miUndoCtx.addActionListener(e -> undoLastOperation());
        miCopy.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, menuMask));
        miPaste.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, menuMask));
        miUndoCtx.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask));

        cellMenu.add(miCopy);
        cellMenu.add(miPaste);
        cellMenu.addSeparator();
        cellMenu.add(miInterp);
        cellMenu.add(miSmooth);
        cellMenu.addSeparator();
        cellMenu.add(miSetValue);
        cellMenu.add(miSetZero);
        cellMenu.add(miFillRight);
        cellMenu.add(miFillDown);
        cellMenu.addSeparator();
        cellMenu.add(miUndoCtx);

        grid.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { if (e.isPopupTrigger()) cellMenu.show(grid, e.getX(), e.getY()); }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) cellMenu.show(grid, e.getX(), e.getY()); }
        });

        updateTableStats();
    }

    private void bindGridKey(KeyStroke keyStroke, String actionKey, Runnable action) {
        grid.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(keyStroke, actionKey);
        grid.getActionMap().put(actionKey, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    /**
     * Commits an in-progress cell edit before a bulk operation.
     *
     * @return false if the edit is invalid and the operation should not proceed
     */
    private boolean finishCellEdit(String operation) {
        if (grid.isEditing() && !grid.getCellEditor().stopCellEditing()) {
            statusLabel.setText("Finish editing the current cell before " + operation + ".");
            return false;
        }
        return true;
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

    /**
     * Sets the raw value at the given model cell, quantized to what the storage
     * type can actually hold (rounded and saturated for integers, narrowed for
     * floats) so the grid always shows exactly what Save will write.
     */
    private void setRawValue(int modelRow, int modelCol, double raw) {
        raw = table.getDataType().quantizeRaw(raw);
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
     * Converts a user-entered physical value to the raw value that will be stored.
     *
     * @throws IllegalArgumentException with a user-facing message if the value is
     *         not finite or does not fit the table's storage type
     */
    private double physicalToStoredRaw(double physical) {
        if (!Double.isFinite(physical)) {
            throw new IllegalArgumentException("Value must be a finite number.");
        }
        double raw = table.toRaw(physical);
        DensoTableType type = table.getDataType();
        if (!type.isRawInRange(raw)) {
            double a = table.toPhysical(type.getMinRaw());
            double b = table.toPhysical(type.getMaxRaw());
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s is outside the %s range (%s to %s).",
                    formatValue(physical), type.getDisplayName(),
                    formatValue(Math.min(a, b)), formatValue(Math.max(a, b))));
        }
        return type.quantizeRaw(raw);
    }

    /** Parses user-entered physical text into the raw value that will be stored. */
    private double parseStoredRaw(String text) {
        double physical;
        try {
            physical = Double.parseDouble(text.trim());
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text.trim() + "' is not a number.");
        }
        return physicalToStoredRaw(physical);
    }

    /** Cell-editor validator: returns an error message (also shown in the status bar) or null. */
    private String validateEditText(String text) {
        try {
            parseStoredRaw(text);
            return null;
        }
        catch (IllegalArgumentException e) {
            statusLabel.setText(e.getMessage());
            return e.getMessage();
        }
    }

    /**
     * Fires a full table data changed event and then restores the previous cell
     * selection (which JTable clears on fireTableDataChanged).
     */
    private void fireAndRestoreSelection() {
        int[] selRows = grid.getSelectedRows();
        int[] selCols = grid.getSelectedColumns();
        tableModel.fireTableDataChanged();  // triggers refreshHeatRange + dirty state via listener
        grid.clearSelection();
        for (int r : selRows) grid.addRowSelectionInterval(r, r);
        for (int c : selCols) grid.addColumnSelectionInterval(c, c);
    }

    // =========================================================================
    // Cell editing operations
    // =========================================================================

    /**
     * Adds {@code delta} (in physical units) to every selected data cell,
     * saturating at the storage type's range. For integer storage a non-zero
     * delta always moves a cell by at least one raw step, so fine wheel steps
     * still work on coarse tables. Uses per-cell updates to avoid clearing the
     * selection.
     *
     * @return the number of cells whose value changed
     */
    private int adjustSelectedCells(double delta) {
        DensoTableType type = table.getDataType();
        int modified = 0;
        for (int r : grid.getSelectedRows()) {
            int mr = grid.convertRowIndexToModel(r);
            for (int c : grid.getSelectedColumns()) {
                int mc = grid.convertColumnIndexToModel(c);
                double raw = getRawValue(mr, mc);
                if (Double.isNaN(raw)) continue;
                double target = table.toRaw(table.toPhysical(raw) + delta);
                if (!Double.isFinite(target)) continue;
                double next = type.quantizeRaw(target);
                if (next == raw && type.isIntegral() && target != raw) {
                    next = type.quantizeRaw(raw + Math.signum(target - raw));
                }
                if (next == raw) continue;
                setRawValue(mr, mc, next);
                tableModel.fireTableCellUpdated(mr, mc);
                modified++;
            }
        }
        return modified;
    }

    /**
     * Linearly interpolates between the first and last selected value within
     * each selected data row.  All selected cells in between are overwritten.
     */
    private void interpolateSelected() {
        runSelectionOperation("interpolate", "Interpolated",
                this::interpolateRows, this::interpolateColumns);
    }

    /**
     * Applies a single 3-point equal-weight moving average to the interior of
     * each selected data row. Endpoint cells are held fixed as anchors.
     */
    private void smoothSelected() {
        runSelectionOperation("smooth", "Smoothed",
                this::smoothRows, this::smoothColumns);
    }

    private void runSelectionOperation(String undoDescription, String pastTenseVerb,
            Supplier<OperationStats> rowOperation,
            Supplier<OperationStats> columnOperation) {
        if (!finishCellEdit("applying curve tools")) {
            return;
        }

        OperationAxis axis = resolveOperationAxis(false);
        if (axis == null) {
            return;
        }
        saveUndoState(undoDescription);

        OperationStats stats = switch (axis) {
            case ROWS -> rowOperation.get();
            case COLUMNS -> columnOperation.get();
            default -> new OperationStats();
        };

        if (!stats.changed()) {
            discardLastUndoState();
            return;
        }

        fireAndRestoreSelection();
        statusLabel.setText(String.format("%s %d span%s across %s.",
                pastTenseVerb,
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
            OperationAxis inferred = inferAutoOperationAxis(modelRows.length, modelCols.length);
            if (inferred != null) {
                return inferred;
            }
            if (!quiet) {
                statusLabel.setText("Selection is too small for that operation.");
            }
            return null;
        }
        return requested;
    }

    private OperationAxis inferAutoOperationAxis(int editableRowCount, int editableColumnCount) {
        if (editableColumnCount > 1 && editableRowCount <= 1) {
            return OperationAxis.ROWS;
        }
        if (editableRowCount > 1 && editableColumnCount <= 1) {
            return OperationAxis.COLUMNS;
        }
        if (editableRowCount > 1 && editableColumnCount > 1) {
            return editableColumnCount >= editableRowCount
                    ? OperationAxis.ROWS
                    : OperationAxis.COLUMNS;
        }
        return null;
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
        return buildRowHeaderTable(t2d.getCountY(),
                row -> formatAxisValue(t2d.getValuesY()[row]));
    }

    private JTable buildRowHeader(DensoTable1D t1d) {
        return buildRowHeaderTable(2, row -> row == 0 ? "X Axis" : "Value");
    }

    private JTable buildRowHeaderTable(int rowCount, IntFunction<String> valueProvider) {
        DefaultTableModel rhModel = new DefaultTableModel(rowCount, 1) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Object getValueAt(int r, int c) { return valueProvider.apply(r); }
        };
        rowHeaderModel = rhModel;

        JTable rh = new JTable(rhModel);
        rh.setBackground(GhidraTheme.tableHeaderBackground());
        rh.setForeground(GhidraTheme.tableHeaderForeground());
        rh.setFont(UI_FONT_BOLD);
        rh.setRowHeight(currentRowHeight);
        rh.setPreferredScrollableViewportSize(new Dimension(currentRowHeaderWidth, 0));
        rh.setDefaultRenderer(Object.class, createRowHeaderRenderer());
        rh.getColumnModel().getColumn(0).setPreferredWidth(currentRowHeaderWidth);
        rh.setTableHeader(null);
        return rh;
    }

    private TableCellRenderer createRowHeaderRenderer() {
        return new DefaultTableCellRenderer() {
            {
                setHorizontalAlignment(SwingConstants.CENTER);
                setFont(UI_FONT_BOLD);
            }
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v,
                    boolean s, boolean f, int r, int c) {
                super.getTableCellRendererComponent(t, v, false, false, r, c);
                setOpaque(true);
                setForeground(GhidraTheme.tableHeaderForeground());
                setBackground(r % 2 == 0
                        ? GhidraTheme.tableHeaderStripeBackground()
                        : GhidraTheme.tableHeaderBackground());
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 1, GhidraTheme.subtleBorderColor()),
                        BorderFactory.createEmptyBorder(0, 4, 0, 4)));
                return this;
            }
        };
    }

    private JLabel buildCornerLabel() {
        JLabel l = new JLabel("Y\\X", SwingConstants.CENTER);
        l.setFont(UI_FONT_SMALL.deriveFont(Font.BOLD | Font.ITALIC));
        l.setForeground(GhidraTheme.secondaryForeground());
        l.setBackground(GhidraTheme.surfaceBackground());
        l.setOpaque(true);
        l.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, GhidraTheme.subtleBorderColor()));
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

        // If user has locked zoom via Ctrl+/-, skip recalculation of cell width
        if (userZoomLocked) {
            // Just derive height proportionally from locked width
            currentRowHeight = clamp((int) (currentCellWidth * 0.35), 20, 30);
            currentRowHeaderWidth = autoSizeRowHeaderWidth();
        } else {
            int cols = Math.max(1, tableModel.getColumnCount());
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
                    targetWidth = 58;
                    targetHeight = 24;
                }
                case COMFORTABLE -> {
                    targetWidth = 90;
                    targetHeight = 30;
                }
                case AUTO -> {
                    // Continuous calculation: derive from viewport width
                    if (tableScrollPane != null) {
                        int viewportWidth = tableScrollPane.getViewport().getWidth();
                        if (viewportWidth > 0) {
                            targetWidth = clamp((viewportWidth - 20) / cols, 46, 96);
                        } else {
                            targetWidth = 76;
                        }
                    } else {
                        targetWidth = 76;
                    }
                    // Derive height proportionally
                    targetHeight = clamp((int) (targetWidth * 0.36), 22, 32);
                }
                default -> throw new IllegalStateException("Unhandled density: " + density);
            }

            currentCellWidth = clamp(targetWidth, 46, 96);
            currentRowHeight = clamp(targetHeight, 22, 32);
            currentRowHeaderWidth = autoSizeRowHeaderWidth();
        }

        // Proportional font scaling: map [46..96] cell width to roughly [-1.5..0]
        float fontDelta = -1.5f * (1f - (currentCellWidth - 46f) / 50f);
        fontDelta = Math.max(-1.5f, Math.min(0f, fontDelta));
        Font tableFont = scaleFont(GhidraTheme.tableFont(), fontDelta);
        Font headerFont = scaleFont(GhidraTheme.tableHeaderFont(), fontDelta);
        Font cornerFont = scaleFont(GhidraTheme.smallFont(), fontDelta * 0.5f)
                .deriveFont(Font.BOLD | Font.ITALIC);

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

    /** Auto-sizes row header width based on actual content. */
    private int autoSizeRowHeaderWidth() {
        if (rowHeaderTable == null) {
            return table.is2D() ? clamp(currentCellWidth + 14, 60, 96)
                                : clamp(currentCellWidth + 18, 64, 100);
        }
        FontMetrics fm = rowHeaderTable.getFontMetrics(rowHeaderTable.getFont());
        int maxWidth = 0;
        for (int r = 0; r < rowHeaderTable.getRowCount(); r++) {
            Object val = rowHeaderTable.getValueAt(r, 0);
            if (val != null) {
                maxWidth = Math.max(maxWidth, fm.stringWidth(val.toString()));
            }
        }
        return clamp(maxWidth + 18, 60, 124);
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
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        if (table.is2D()) {
            for (double[] row : ((DensoTable2D) table).getValuesZ()) {
                for (double raw : row) {
                    double v = table.toPhysical(raw);
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
            }
        } else {
            for (double raw : ((DensoTable1D) table).getValuesY()) {
                double v = table.toPhysical(raw);
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        if (min > max) {
            min = max = 0;  // empty table
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
                BorderFactory.createLineBorder(GhidraTheme.subtleBorderColor()),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        f.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                f.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(GhidraTheme.focusRingColor()),
                        BorderFactory.createEmptyBorder(4, 6, 4, 6)));
            }
            @Override public void focusLost(FocusEvent e) {
                f.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(GhidraTheme.subtleBorderColor()),
                        BorderFactory.createEmptyBorder(4, 6, 4, 6)));
            }
        });
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
            refreshGridFromModel();
            markMacDirty();
            statusLabel.setText("Updated MAC header fields. Raw table data is unchanged.");
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
            int mr = grid.convertRowIndexToModel(r);
            for (int c : cols) {
                int mc = grid.convertColumnIndexToModel(c);
                // Only data cells count; the 1-D X-axis row is read-only.
                if (!tableModel.isCellEditable(mr, mc)) continue;
                double raw = getRawValue(mr, mc);
                if (Double.isNaN(raw)) continue;
                double v = table.toPhysical(raw);
                count++; sum += v;
                minV = Math.min(minV, v); maxV = Math.max(maxV, v);
                lastVal = v;
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
                axisHint = "Selection is too small for curve tools.";
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
    // Undo support (multi-level, raw payload only)
    // =========================================================================

    /** Records the current raw payload so the next edit can be undone. */
    private void saveUndoState(String description) {
        wheelUndoOpen = false;
        double[] values1D = null;
        double[][] values2D = null;
        if (table.is2D()) {
            double[][] z = ((DensoTable2D) table).getValuesZ();
            values2D = new double[z.length][];
            for (int y = 0; y < z.length; y++) {
                values2D[y] = z[y].clone();
            }
        } else {
            values1D = ((DensoTable1D) table).getValuesY().clone();
        }
        undoStack.push(new UndoSnapshot(description, values1D, values2D));
        while (undoStack.size() > MAX_UNDO_LEVELS) {
            undoStack.removeLast();
        }
    }

    /** Drops the newest undo entry when the operation it guarded changed nothing. */
    private void discardLastUndoState() {
        undoStack.poll();
        wheelUndoOpen = false;
    }

    private void undoLastOperation() {
        if (grid.isEditing()) {
            // Undo while typing reverts the typing, not the table.
            grid.getCellEditor().cancelCellEditing();
            return;
        }
        UndoSnapshot snapshot = undoStack.poll();
        wheelUndoOpen = false;
        if (snapshot == null) {
            statusLabel.setText("Nothing to undo.");
            return;
        }
        if (table.is2D()) {
            DensoTable2D t2d = (DensoTable2D) table;
            double[][] values = snapshot.values2D();
            for (int y = 0; y < values.length; y++) {
                for (int x = 0; x < values[y].length; x++) {
                    t2d.setZ(y, x, values[y][x]);
                }
            }
        } else {
            double[] ys = ((DensoTable1D) table).getValuesY();
            double[] values = snapshot.values1D();
            System.arraycopy(values, 0, ys, 0, Math.min(ys.length, values.length));
        }
        fireAndRestoreSelection();
        statusLabel.setText("Undid " + snapshot.description()
                + (undoStack.isEmpty() ? "." : " (" + undoStack.size() + " more)."));
    }

    // =========================================================================
    // Context menu operations
    // =========================================================================

    private void copySelectedCells() {
        int[] rows = grid.getSelectedRows();
        int[] cols = grid.getSelectedColumns();
        if (rows.length == 0 || cols.length == 0) return;
        StringBuilder sb = new StringBuilder();
        for (int r : rows) {
            for (int i = 0; i < cols.length; i++) {
                if (i > 0) sb.append('\t');
                Object val = tableModel.getValueAt(
                        grid.convertRowIndexToModel(r),
                        grid.convertColumnIndexToModel(cols[i]));
                sb.append(val != null ? val : "");
            }
            sb.append('\n');
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(sb.toString()), null);
        statusLabel.setText("Copied " + rows.length + "\u00D7" + cols.length + " cells.");
    }

    /**
     * Pastes tab/newline separated values (e.g. from a spreadsheet).
     * <ul>
     *   <li>A single clipboard value fills every selected cell.</li>
     *   <li>With a single selected cell, the whole block is pasted anchored there.</li>
     *   <li>Otherwise the block is clipped to the selection.</li>
     * </ul>
     * Blank clipboard cells leave the target unchanged; invalid or out-of-range
     * values are skipped and reported.
     */
    private void pasteIntoCells() {
        if (!finishCellEdit("pasting")) return;

        String text;
        try {
            text = (String) Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(DataFlavor.stringFlavor);
        }
        catch (UnsupportedFlavorException | IOException | IllegalStateException ex) {
            statusLabel.setText("Clipboard does not contain text.");
            return;
        }
        String[][] cells = parseClipboardGrid(text);
        if (cells.length == 0) {
            statusLabel.setText("Clipboard is empty.");
            return;
        }

        int[] rows = grid.getSelectedRows();
        int[] cols = grid.getSelectedColumns();
        if (rows.length == 0 || cols.length == 0) {
            statusLabel.setText("Select a cell to paste into.");
            return;
        }

        // Each target is {viewRow, viewCol, clipboardRow, clipboardCol}.
        List<int[]> targets = new ArrayList<>();
        if (cells.length == 1 && cells[0].length == 1) {
            for (int r : rows) {
                for (int c : cols) {
                    targets.add(new int[] { r, c, 0, 0 });
                }
            }
        }
        else if (rows.length == 1 && cols.length == 1) {
            for (int ri = 0; ri < cells.length && rows[0] + ri < grid.getRowCount(); ri++) {
                for (int ci = 0; ci < cells[ri].length && cols[0] + ci < grid.getColumnCount(); ci++) {
                    targets.add(new int[] { rows[0] + ri, cols[0] + ci, ri, ci });
                }
            }
        }
        else {
            for (int ri = 0; ri < Math.min(cells.length, rows.length); ri++) {
                for (int ci = 0; ci < Math.min(cells[ri].length, cols.length); ci++) {
                    targets.add(new int[] { rows[ri], cols[ci], ri, ci });
                }
            }
        }

        saveUndoState("paste");
        int pasted = 0;
        int skipped = 0;
        String firstError = null;
        for (int[] target : targets) {
            int mr = grid.convertRowIndexToModel(target[0]);
            int mc = grid.convertColumnIndexToModel(target[1]);
            String value = cells[target[2]][target[3]].trim();
            if (value.isEmpty() || !tableModel.isCellEditable(mr, mc)) continue;
            try {
                setRawValue(mr, mc, parseStoredRaw(value));
                pasted++;
            }
            catch (IllegalArgumentException ex) {
                skipped++;
                if (firstError == null) firstError = ex.getMessage();
            }
        }

        if (pasted > 0) {
            fireAndRestoreSelection();
        }
        else {
            discardLastUndoState();
        }
        String message = "Pasted " + pasted + " cell(s).";
        if (skipped > 0) {
            message += " Skipped " + skipped + ": " + firstError;
        }
        statusLabel.setText(message);
    }

    /** Splits tab/newline separated text into rows of cells; trailing empty lines are dropped. */
    private static String[][] parseClipboardGrid(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0][];
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        String[][] cells = new String[lines.length][];
        for (int i = 0; i < lines.length; i++) {
            cells[i] = lines[i].split("\t", -1);
        }
        return cells;
    }

    private void setValueDialog() {
        if (!finishCellEdit("setting values")) return;
        String input = JOptionPane.showInputDialog(this, "Set all selected cells to:", "Set Value", JOptionPane.PLAIN_MESSAGE);
        if (input == null) return;
        double val;
        try {
            val = Double.parseDouble(input.trim());
        } catch (NumberFormatException ex) {
            statusLabel.setText("'" + input.trim() + "' is not a number.");
            return;
        }
        fillSelectedCells(val, "set-value");
    }

    private void fillSelectedCells(double physicalValue, String undoDescription) {
        if (!finishCellEdit("setting values")) return;
        double raw;
        try {
            raw = physicalToStoredRaw(physicalValue);
        }
        catch (IllegalArgumentException ex) {
            statusLabel.setText(ex.getMessage());
            return;
        }

        saveUndoState(undoDescription);
        int modified = 0;
        for (int r : grid.getSelectedRows()) {
            int mr = grid.convertRowIndexToModel(r);
            for (int c : grid.getSelectedColumns()) {
                int mc = grid.convertColumnIndexToModel(c);
                if (tableModel.isCellEditable(mr, mc)) {
                    setRawValue(mr, mc, raw);
                    modified++;
                }
            }
        }
        if (modified > 0) {
            fireAndRestoreSelection();
            statusLabel.setText("Set " + modified + " cell(s) to " + formatValue(table.toPhysical(raw)) + ".");
        }
        else {
            discardLastUndoState();
        }
    }

    private void fillRight() {
        if (!finishCellEdit("filling")) return;
        int[] rows = grid.getSelectedRows();
        int[] cols = grid.getSelectedColumns();
        if (cols.length < 2) return;
        saveUndoState("fill-right");
        int modified = 0;
        for (int r : rows) {
            int mr = grid.convertRowIndexToModel(r);
            int srcMc = grid.convertColumnIndexToModel(cols[0]);
            double raw = getRawValue(mr, srcMc);
            if (Double.isNaN(raw)) continue;
            for (int ci = 1; ci < cols.length; ci++) {
                int mc = grid.convertColumnIndexToModel(cols[ci]);
                if (tableModel.isCellEditable(mr, mc)) {
                    setRawValue(mr, mc, raw);
                    modified++;
                }
            }
        }
        if (modified > 0) {
            fireAndRestoreSelection();
            statusLabel.setText("Filled right: " + modified + " cell(s).");
        }
        else {
            discardLastUndoState();
        }
    }

    private void fillDown() {
        if (!finishCellEdit("filling")) return;
        int[] rows = grid.getSelectedRows();
        int[] cols = grid.getSelectedColumns();
        if (rows.length < 2) return;
        saveUndoState("fill-down");
        int modified = 0;
        int srcRow = grid.convertRowIndexToModel(rows[0]);
        for (int c : cols) {
            int mc = grid.convertColumnIndexToModel(c);
            double raw = getRawValue(srcRow, mc);
            if (Double.isNaN(raw)) continue;
            for (int ri = 1; ri < rows.length; ri++) {
                int mr = grid.convertRowIndexToModel(rows[ri]);
                if (tableModel.isCellEditable(mr, mc)) {
                    setRawValue(mr, mc, raw);
                    modified++;
                }
            }
        }
        if (modified > 0) {
            fireAndRestoreSelection();
            statusLabel.setText("Filled down: " + modified + " cell(s).");
        }
        else {
            discardLastUndoState();
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
        if (!finishCellEdit("saving")) return;
        if (!dirty) return;
        if (program.isClosed()) {
            Msg.showWarn(this, this, "Program Closed",
                    "The program for this table has been closed; changes cannot be saved.");
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
        String savedMessage = null;
        try {
            boolean wroteData = dataDirty;
            boolean wroteMacHeader = macDirty && table.isHasMAC();
            Memory mem = program.getMemory();
            if (wroteData) {
                if (table.is2D()) write2D((DensoTable2D) table, mem);
                else              write1D((DensoTable1D) table, mem);
            }
            if (wroteMacHeader) {
                writeMacHeader(table, mem);
            }
            success = true;
            if (wroteData && wroteMacHeader) {
                savedMessage = "Saved table data and MAC header to ROM.";
            }
            else if (wroteData) {
                savedMessage = "Saved table data to ROM.";
            }
            else if (wroteMacHeader) {
                savedMessage = "Saved MAC header to ROM. Raw table data is unchanged.";
            }
            else {
                savedMessage = "No ROM changes were pending.";
            }
        } catch (Exception ex) {
            Msg.showError(this, this, "Write Error",
                    "Failed to write table: " + ex.getMessage(), ex);
        } finally {
            program.endTransaction(tx, success);
        }

        if (success) {
            // Re-read what was actually committed so the grid mirrors the program.
            loadFromRom();
            syncMacUi();
            clearDirtyState();
            refreshGridFromModel();
            statusLabel.setText(savedMessage);
            saveListener.accept(table);
        }
    }

    private void write2D(DensoTable2D t2d, Memory mem) throws Exception {
        AddressSpace space = program.getAddressFactory().getDefaultAddressSpace();
        int countX = t2d.getCountX();
        int countY = t2d.getCountY();
        byte[] raw = encodeDataBuffer(t2d.getDataType(), countX * countY,
                index -> t2d.getZ(index / countX, index % countX),
                index -> "Invalid value at row " + (index / countX + 1) +
                        ", column " + (index % countX + 1) + ": ");
        mem.setBytes(space.getAddress(t2d.getPtrZ()), raw);
    }

    private void write1D(DensoTable1D t1d, Memory mem) throws Exception {
        AddressSpace space = program.getAddressFactory().getDefaultAddressSpace();
        byte[] raw = encodeDataBuffer(t1d.getDataType(), t1d.getCountX(),
                index -> t1d.getValuesY()[index],
                index -> "Invalid value at column " + (index + 1) + ": ");
        mem.setBytes(space.getAddress(t1d.getPtrY()), raw);
    }

    private byte[] encodeDataBuffer(DensoTableType dataType, int valueCount,
            IntToDoubleFunction valueProvider, IntFunction<String> errorPrefixProvider)
            throws Exception {
        int elemSize = dataType.getValueSize();
        byte[] raw = new byte[valueCount * elemSize];
        for (int index = 0; index < valueCount; index++) {
            long bits;
            try {
                bits = dataType.doubleToRaw(valueProvider.applyAsDouble(index));
            }
            catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                        errorPrefixProvider.apply(index) + ex.getMessage(), ex);
            }
            writeBigEndian(raw, index * elemSize, bits, elemSize);
        }
        return raw;
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
                int cx = t2d.getCountX();
                int cy = t2d.getCountY();
                t2d.setValuesX(readFloatArray(mem, space, t2d.getPtrX(), cx));
                t2d.setValuesY(readFloatArray(mem, space, t2d.getPtrY(), cy));
                t2d.setValuesZ(readDataMatrix(mem, space, t2d.getPtrZ(), cx, cy, dtype));
            } else {
                DensoTable1D t1d = (DensoTable1D) table;
                DensoTableType dtype = t1d.getDataType();
                int count = t1d.getCountX();
                t1d.setValuesX(readFloatArray(mem, space, t1d.getPtrX(), count));
                t1d.setValuesY(readDataArray(mem, space, t1d.getPtrY(), count, dtype));
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

    private float[] readFloatArray(Memory mem, AddressSpace space, long ptr, int count)
            throws Exception {
        byte[] raw = new byte[count * 4];
        mem.getBytes(space.getAddress(ptr), raw);
        float[] values = new float[count];
        for (int i = 0; i < count; i++) {
            values[i] = readFloatBE(raw, i * 4);
        }
        return values;
    }

    private double[] readDataArray(Memory mem, AddressSpace space, long ptr,
            int count, DensoTableType dataType) throws Exception {
        int elemSize = dataType.getValueSize();
        byte[] raw = new byte[count * elemSize];
        mem.getBytes(space.getAddress(ptr), raw);
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = dataType.rawToDouble(readBigEndian(raw, i * elemSize, elemSize));
        }
        return values;
    }

    private double[][] readDataMatrix(Memory mem, AddressSpace space, long ptr,
            int countX, int countY, DensoTableType dataType) throws Exception {
        int elemSize = dataType.getValueSize();
        byte[] raw = new byte[countX * countY * elemSize];
        mem.getBytes(space.getAddress(ptr), raw);
        double[][] values = new double[countY][countX];
        for (int row = 0; row < countY; row++) {
            for (int col = 0; col < countX; col++) {
                values[row][col] = dataType.rawToDouble(
                        readBigEndian(raw, (row * countX + col) * elemSize, elemSize));
            }
        }
        return values;
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
        clearDirtyState();
        undoStack.clear();
        wheelUndoOpen = false;
        refreshGridFromModel();
        statusLabel.setText("Reverted from ROM.");
    }

    /**
     * Exports physical values as CSV. 2-D tables get the X axis as a header row
     * and the Y axis as the first column; 1-D tables get labelled X/value rows.
     */
    private void exportCsv() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(table.getName() + ".csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = fc.getSelectedFile();
        if (file.exists()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    file.getName() + " already exists. Replace it?", "Export CSV",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
        }

        try (PrintWriter pw = new PrintWriter(file, StandardCharsets.UTF_8)) {
            float[] xs = table.getValuesX();
            if (table.is2D()) {
                DensoTable2D t2d = (DensoTable2D) table;
                StringBuilder header = new StringBuilder("Y\\X");
                for (float x : xs) header.append(',').append(formatExactAxisValue(x));
                pw.println(header);
                float[] ys = t2d.getValuesY();
                for (int r = 0; r < t2d.getCountY(); r++) {
                    StringBuilder sb = new StringBuilder(r < ys.length ? formatExactAxisValue(ys[r]) : "");
                    for (int c = 0; c < t2d.getCountX(); c++) {
                        sb.append(',').append(formatValue(t2d.toPhysical(t2d.getZ(r, c))));
                    }
                    pw.println(sb);
                }
            }
            else {
                double[] values = ((DensoTable1D) table).getValuesY();
                StringBuilder axis = new StringBuilder("X");
                StringBuilder data = new StringBuilder("Value");
                for (float x : xs) axis.append(',').append(formatExactAxisValue(x));
                for (double v : values) data.append(',').append(formatValue(table.toPhysical(v)));
                pw.println(axis);
                pw.println(data);
            }
            if (pw.checkError()) {
                throw new IOException("Failed writing " + file.getName());
            }
            statusLabel.setText("Exported \u2192 " + file.getName());
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

    private void markDataDirty() {
        dataDirty = true;
        dirty = true;
        saveBtn.setEnabled(program != null);
        revertBtn.setEnabled(true);
        setTitle(table.getName() + " - GhidraTables [modified]");
    }

    private void markMacDirty() {
        macDirty = true;
        dirty = true;
        saveBtn.setEnabled(program != null);
        revertBtn.setEnabled(true);
        setTitle(table.getName() + " - GhidraTables [modified]");
    }

    private void clearDirtyState() {
        dirty = false;
        dataDirty = false;
        macDirty = false;
        saveBtn.setEnabled(false);
        revertBtn.setEnabled(false);
        setTitle(table.getName() + " - GhidraTables");
    }

    // =========================================================================
    // Table models
    // =========================================================================

    /**
     * Shared cell-write path: parses physical text, validates it against the
     * storage type, and stores the quantized raw value. Text identical to the
     * current display is ignored, so re-committing a rounded display value
     * never rewrites the underlying data.
     */
    private abstract class RawValueTableModel extends AbstractTableModel {
        @Override public void setValueAt(Object aValue, int row, int col) {
            if (aValue == null || !isCellEditable(row, col)) return;
            String text = aValue.toString().trim();
            if (text.equals(getValueAt(row, col))) return;
            double raw;
            try {
                raw = parseStoredRaw(text);
            }
            catch (IllegalArgumentException ex) {
                statusLabel.setText(ex.getMessage());
                return;
            }
            if (raw == getRawValue(row, col)) return;
            setRawValue(row, col, raw);
            fireTableCellUpdated(row, col);
        }
    }

    private class Table2DModel extends RawValueTableModel {
        private final DensoTable2D t2d;
        Table2DModel(DensoTable2D t2d) { this.t2d = t2d; }

        @Override public int getRowCount()    { return t2d.getCountY(); }
        @Override public int getColumnCount() { return t2d.getCountX(); }
        @Override public String getColumnName(int col) { return formatAxisValue(t2d.getValuesX()[col]); }

        @Override public Object getValueAt(int row, int col) {
            return formatValue(t2d.toPhysical(t2d.getZ(row, col)));
        }

        @Override public boolean isCellEditable(int row, int col) { return true; }
    }

    private class Table1DModel extends RawValueTableModel {
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
        if (!Double.isFinite(v)) return Double.toString(v);
        if (v == 0) return "0";  // also normalizes -0.0
        if (Math.abs(v) < 1e-3) {
            // Keep significant digits for tiny values instead of rounding to 0.
            String s = String.format(Locale.ROOT, "%.6e", v);
            int e = s.indexOf('e');
            return trimTrailingZeros(s.substring(0, e)) + s.substring(e);
        }
        return trimTrailingZeros(String.format(Locale.ROOT, "%.6f", v));
    }

    /** Shortest decimal that round-trips to {@code v}, without an exponent (for export). */
    private static String formatExactAxisValue(float v) {
        if (!Float.isFinite(v)) return Float.toString(v);
        return new BigDecimal(Float.toString(v)).stripTrailingZeros().toPlainString();
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
        int h    = Math.min(rows * 24 + 130, 950);  // less overhead since info/action bars removed
        return new Dimension(Math.max(w, 920), Math.max(h, 500));
    }
}
