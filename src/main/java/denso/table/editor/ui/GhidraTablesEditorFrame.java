/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor.ui;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.io.*;
import java.util.Arrays;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.table.*;

import denso.table.editor.DensoStructureApplier;
import denso.table.editor.DensoTableIO;
import denso.table.editor.model.*;
import ghidra.app.services.GoToService;
import ghidra.framework.plugintool.PluginTool;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.Program;
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

    // Inspector selection-card action buttons
    private JButton inspInterpolateBtn;
    private JButton inspSmoothBtn;

    // 3D view components
    private Surface3DPanel surface3DPanel;
    private JToggleButton view3DToggle;
    private JPanel viewCards;
    private CardLayout viewCardLayout;
    private boolean showing3D = false;
    private static final String CARD_GRID = "grid";
    private static final String CARD_3D = "3d";

    // Collapsible overview
    private JPanel overviewContent;
    private JButton overviewToggleBtn;

    private final TableEditHistory history;
    private Runnable savedListener = () -> {};

    // Zoom override for density
    private boolean userZoomLocked = false;

    /** True whenever in-memory state differs from the last saved ROM state. */
    private boolean dirty = false;
    /** True when raw table payload bytes have been modified in the editor. */
    private boolean dataDirty = false;
    /** True when only the MAC header fields have been modified in the editor. */
    private boolean macDirty = false;

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
        if (program != null) {
            try {
                DensoTableIO.reload(program, table);
            } catch (Exception ex) {
                throw new IllegalStateException("Unable to read table from ROM: " + ex.getMessage(), ex);
            }
        }
        history = new TableEditHistory(table);

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

        view3DToggle = makeToolbarToggle("3D",
                "Toggle 3D surface view (read-only)", false);
        view3DToggle.addActionListener(e -> setView3D(view3DToggle.isSelected()));

        inspectorToggle = makeToolbarToggle("Inspector",
                "Show or hide the inspector panel", true);
        inspectorToggle.addActionListener(e -> setInspectorVisible(inspectorToggle.isSelected()));

        rightGroup.add(makeToolbarControl("Density", densityCombo));
        if (table.is2D()) {
            rightGroup.add(makeToolbarControl("Axis", axisCombo));
        }
        rightGroup.add(view3DToggle);
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

        // Wrap in a CardLayout so we can toggle between grid and 3D view
        viewCardLayout = new CardLayout();
        viewCards = new JPanel(viewCardLayout);
        viewCards.add(workspace, CARD_GRID);

        inspectorScrollPane = new JScrollPane(buildInspectorPanel());
        inspectorScrollPane.setBorder(BorderFactory.createEmptyBorder());
        inspectorScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        inspectorScrollPane.getViewport().setBackground(GhidraTheme.surfaceBackground());

        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, viewCards, inspectorScrollPane);
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

        Runnable toggle = () -> {
            boolean show = !content.isVisible();
            content.setVisible(show);
            overviewToggleBtn.setText(show ? "\u25BC" : "\u25B6");
            summaryLabel.setVisible(!show);
            card.revalidate();
        };
        overviewToggleBtn.addActionListener(e -> toggle.run());
        header.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { toggle.run(); }
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

        javax.swing.event.DocumentListener macTextListener = new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { refreshDirtyState(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { refreshDirtyState(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { refreshDirtyState(); }
        };
        multField.getDocument().addDocumentListener(macTextListener);
        offField.getDocument().addDocumentListener(macTextListener);

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

        editor = new MultiEditTableCellEditor(this::commitSelectedValue);
        editor.setChangeListener(this::refreshDirtyState);
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
            updateTableStats();
            updateStatus();
            if (showing3D && surface3DPanel != null) {
                surface3DPanel.refresh(table);
            }
        });

        // Only wheel over selected data adjusts values; everywhere else scrolls.
        grid.addMouseWheelListener(e -> {
            int row = grid.rowAtPoint(e.getPoint());
            int col = grid.columnAtPoint(e.getPoint());
            if (row < 0 || col < 0 || !grid.isCellSelected(row, col)
                    || !tableModel.isCellEditable(grid.convertRowIndexToModel(row),
                            grid.convertColumnIndexToModel(col))) {
                if (tableScrollPane != null) {
                    Point point = SwingUtilities.convertPoint(grid, e.getPoint(), tableScrollPane);
                    tableScrollPane.dispatchEvent(new MouseWheelEvent(tableScrollPane, e.getID(),
                            e.getWhen(), e.getModifiersEx(), point.x, point.y,
                            e.getXOnScreen(), e.getYOnScreen(), e.getClickCount(), e.isPopupTrigger(),
                            e.getScrollType(), e.getScrollAmount(), e.getWheelRotation(),
                            e.getPreciseWheelRotation()));
                }
                return;
            }
            e.consume();
            if (!finishCellEditing()) return;
            double step = e.isControlDown() ? (e.isShiftDown() ? 0.01 : 0.1)
                    : e.isShiftDown() ? 10 : 1;
            String key = Arrays.toString(grid.getSelectedRows()) + ":"
                    + Arrays.toString(grid.getSelectedColumns()) + ":" + step;
            try {
                if (commitEdit("scroll-adjust", key,
                        () -> adjustSelectedCells(-e.getWheelRotation() * step))) {
                    statusLabel.setText("Adjusted selected cells; integer values round to storage units.");
                }
            } catch (IllegalArgumentException ex) {
                statusLabel.setText(ex.getMessage());
            }
        });
        grid.getSelectionModel().addListSelectionListener(e -> history.breakMerge());
        grid.getColumnModel().getSelectionModel().addListSelectionListener(e -> history.breakMerge());

        bindShortcut(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK, "undo", this::undoLastOperation);
        bindShortcut(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK, "redo", this::redoLastOperation);
        bindShortcut(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK,
                "redo", this::redoLastOperation);
        bindShortcut(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK, "copy", this::copySelectedCells);
        bindShortcut(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK, "paste", this::pasteIntoCells);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "save");
        getRootPane().getActionMap().put("save", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { applyChanges(); }
        });

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
        miSetZero.addActionListener(e -> fillSelectedCells(0));
        miFillRight.addActionListener(e -> fillRight());
        miFillDown.addActionListener(e -> fillDown());
        miUndoCtx.addActionListener(e -> undoLastOperation());

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
        raw = table.getDataType().quantize(raw);
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
     * Refreshes all values without clearing the selection or restarting an edit.
     */
    private void fireAndRestoreSelection() {
        tableModel.fireTableRowsUpdated(0, tableModel.getRowCount() - 1);
    }

    private void bindShortcut(int key, int modifiers, String name, Runnable action) {
        grid.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, modifiers), name);
        grid.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    private boolean finishCellEditing() {
        return !grid.isEditing() || grid.getCellEditor().stopCellEditing();
    }

    private boolean commitEdit(String description, Object mergeKey, Runnable operation) {
        boolean changed = history.apply(description, mergeKey, operation);
        if (changed) {
            refreshDirtyState();
            fireAndRestoreSelection();
        }
        return changed;
    }

    private void performEdit(String description, Runnable operation) {
        if (!finishCellEditing()) return;
        try {
            if (commitEdit(description, null, operation)) statusLabel.setText("Applied " + description + ".");
        } catch (IllegalArgumentException ex) {
            statusLabel.setText(ex.getMessage());
        }
    }

    private int commitSelectedValue(double physical, int[] rows, int[] columns) {
        double raw = table.getDataType().quantize(table.toRaw(physical));
        int[] count = {0};
        commitEdit("edit cells", null, () -> {
            for (int row : rows) {
                for (int col : columns) {
                    if (tableModel.isCellEditable(row, col)
                            && Double.compare(getRawValue(row, col), raw) != 0) {
                        setRawValue(row, col, raw);
                        count[0]++;
                    }
                }
            }
        });
        return count[0];
    }

    private void adjustSelectedCells(double delta) {
        for (int row : getSelectedEditableModelRows()) {
            for (int col : getSelectedModelColumns()) {
                double value = table.toRaw(table.toPhysical(getRawValue(row, col)) + delta);
                setRawValue(row, col, table.getDataType().clampAndQuantize(value));
            }
        }
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
        if (grid.isEditing() && !grid.getCellEditor().stopCellEditing()) {
            statusLabel.setText("Finish editing the current cell before applying curve tools.");
            return;
        }

        OperationAxis axis = resolveOperationAxis(false);
        if (axis == null) {
            return;
        }
        try {
            OperationStats[] result = {new OperationStats()};
            boolean changed = commitEdit(undoDescription, null, () -> result[0] = switch (axis) {
                case ROWS -> rowOperation.get();
                case COLUMNS -> columnOperation.get();
                default -> new OperationStats();
            });
            if (changed) {
                statusLabel.setText(String.format("%s %d span%s across %s.", pastTenseVerb,
                        result[0].spans, result[0].spans == 1 ? "" : "s",
                        axis == OperationAxis.ROWS ? "rows" : "columns"));
            }
        } catch (IllegalArgumentException ex) {
            statusLabel.setText(ex.getMessage());
        }
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
            for (int mc : modelCols) {
                if (!tableModel.isCellEditable(mr, mc)) {
                    continue;
                }
                double t = (cFirst == cLast) ? 0.0 : (double) (mc - cFirst) / (cLast - cFirst);
                setRawValue(mr, mc, table.toRaw(physFirst + t * (physLast - physFirst)));
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
            for (int mr : modelRows) {
                if (!tableModel.isCellEditable(mr, mc)) {
                    continue;
                }
                double t = (rFirst == rLast) ? 0.0 : (double) (mr - rFirst) / (rLast - rFirst);
                setRawValue(mr, mc, table.toRaw(physFirst + t * (physLast - physFirst)));
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
            for (int mc : modelCols) {
                if (mc == cFirst || mc == cLast) continue;
                double smoothed = (phys[mc - cFirst - 1] + phys[mc - cFirst] + phys[mc - cFirst + 1]) / 3.0;
                setRawValue(mr, mc, table.toRaw(smoothed));
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
            for (int mr : modelRows) {
                if (mr == rFirst || mr == rLast) continue;
                double smoothed = (phys[mr - rFirst - 1] + phys[mr - rFirst] + phys[mr - rFirst + 1]) / 3.0;
                setRawValue(mr, mc, table.toRaw(smoothed));
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

    private void setView3D(boolean show3D) {
        if (!finishCellEditing()) {
            view3DToggle.setSelected(showing3D);
            return;
        }
        this.showing3D = show3D;
        if (viewCardLayout != null && viewCards != null) {
            if (show3D) {
                if (surface3DPanel == null) {
                    surface3DPanel = new Surface3DPanel();
                    viewCards.add(surface3DPanel, CARD_3D);
                }
                surface3DPanel.refresh(table);
            }
            viewCardLayout.show(viewCards, show3D ? CARD_3D : CARD_GRID);
        }
        if (view3DToggle != null && view3DToggle.isSelected() != show3D) {
            view3DToggle.setSelected(show3D);
        }
    }

    private void refreshHeatRange() {
        DoubleSummaryStatistics stats = new DoubleSummaryStatistics();
        if (table instanceof DensoTable2D t) {
            for (double[] row : t.getValuesZ()) {
                for (double value : row) stats.accept(table.toPhysical(value));
            }
        } else {
            for (double value : ((DensoTable1D) table).getValuesY()) {
                stats.accept(table.toPhysical(value));
            }
        }
        renderer.setRange(stats.getCount() == 0 ? 0 : stats.getMin(),
                stats.getCount() == 0 ? 1 : stats.getMax());
        if (grid != null) grid.repaint();
    }

    private void refreshGridFromModel() {
        tableModel.fireTableDataChanged();
        if (rowHeaderModel != null) {
            rowHeaderModel.fireTableDataChanged();
        }
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
    private boolean commitMacFields() {
        if (!table.isHasMAC() || multField == null) return true;
        if (!finishCellEditing()) return false;
        try {
            float multiplier = Float.parseFloat(multField.getText().trim());
            float offset = Float.parseFloat(offField.getText().trim());
            String error = DensoTable.validateMacParameters(multiplier, offset);
            if (error != null) throw new IllegalArgumentException(error);
            if (Float.compare(table.getMultiplier(), multiplier) == 0
                    && Float.compare(table.getOffset(), offset) == 0) {
                syncMacUi();
                return true;
            }
            commitEdit("MAC parameters", null, () -> {
                table.setMultiplier(multiplier);
                table.setOffset(offset);
            });
            syncMacUi();
            statusLabel.setText("Updated MAC header fields. Raw table data is unchanged.");
            return true;
        } catch (IllegalArgumentException ex) {
            statusLabel.setText(ex.getMessage());
            multField.setForeground(GhidraTheme.errorForeground());
            offField.setForeground(GhidraTheme.errorForeground());
            return false;
        }
    }

    // =========================================================================
    // Status
    // =========================================================================

    private void updateStatus() {
        if (selectionSummaryLabel == null) return;
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
                int row = grid.convertRowIndexToModel(r);
                int col = grid.convertColumnIndexToModel(c);
                if (!tableModel.isCellEditable(row, col)) continue;
                double value = table.toPhysical(getRawValue(row, col));
                count++; sum += value;
                minV = Math.min(minV, value); maxV = Math.max(maxV, value);
                lastVal = value;
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
    // Undo / redo
    // =========================================================================

    private void undoLastOperation() { moveHistory(false); }
    private void redoLastOperation() { moveHistory(true); }

    private void moveHistory(boolean redo) {
        if (!finishCellEditing()) return;
        String description = redo ? history.redo() : history.undo();
        if (description == null) {
            statusLabel.setText(redo ? "Nothing to redo." : "Nothing to undo.");
            return;
        }
        syncMacUi();
        refreshDirtyState();
        fireAndRestoreSelection();
        statusLabel.setText((redo ? "Redid " : "Undid ") + description + ".");
    }

    private void copySelectedCells() {
        if (!finishCellEditing()) return;
        int[] rows = grid.getSelectedRows();
        int[] cols = grid.getSelectedColumns();
        if (rows.length == 0 || cols.length == 0) return;
        StringBuilder sb = new StringBuilder();
        for (int r : rows) {
            for (int i = 0; i < cols.length; i++) {
                if (i > 0) sb.append('\t');
                int mr = grid.convertRowIndexToModel(r);
                int mc = grid.convertColumnIndexToModel(cols[i]);
                sb.append(!table.is2D() && mr == 0 ? table.getValuesX()[mc]
                        : table.toPhysical(getRawValue(mr, mc)));
            }
            sb.append('\n');
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(sb.toString()), null);
        statusLabel.setText("Copied " + rows.length + "\u00D7" + cols.length + " cells.");
    }

    private void pasteIntoCells() {
        if (!finishCellEditing()) return;
        try {
            String text = (String) Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(DataFlavor.stringFlavor);
            if (text == null || text.isBlank()) return;
            pasteText(text);
        } catch (Exception ex) {
            statusLabel.setText("Paste failed: " + ex.getMessage());
        }
    }

    private void pasteText(String text) {
        int[] rows = grid.getSelectedRows();
        int[] cols = grid.getSelectedColumns();
        if (rows.length == 0 || cols.length == 0) return;
        double[][] cells = TableClipboard.parse(text);
        boolean single = cells.length == 1 && cells[0].length == 1;
        boolean anchored = rows.length == 1 && cols.length == 1;
        if (!single && !anchored && (rows.length != cells.length || cols.length != cells[0].length)) {
            throw new IllegalArgumentException("Select one anchor cell or a region matching the clipboard.");
        }
        int height = single ? rows.length : cells.length;
        int width = single ? cols.length : cells[0].length;
        if (anchored && (rows[0] + height > grid.getRowCount()
                || cols[0] + width > grid.getColumnCount())) {
            throw new IllegalArgumentException("Clipboard extends beyond the table.");
        }
        commitEdit("paste", null, () -> {
            for (int r = 0; r < height; r++) {
                for (int c = 0; c < width; c++) {
                    int mr = grid.convertRowIndexToModel(anchored ? rows[0] + r : rows[r]);
                    int mc = grid.convertColumnIndexToModel(anchored ? cols[0] + c : cols[c]);
                    if (!tableModel.isCellEditable(mr, mc)) {
                        throw new IllegalArgumentException("Paste includes read-only axis cells.");
                    }
                    double physical = cells[single ? 0 : r][single ? 0 : c];
                    setRawValue(mr, mc, table.toRaw(physical));
                }
            }
        });
        statusLabel.setText("Pasted " + height * width + " cells.");
    }

    private void setValueDialog() {
        if (!finishCellEditing()) return;
        String input = JOptionPane.showInputDialog(this, "Set all selected cells to:",
                "Set Value", JOptionPane.PLAIN_MESSAGE);
        if (input == null) return;
        try {
            fillSelectedCells(Double.parseDouble(input.trim()));
        } catch (NumberFormatException ex) {
            statusLabel.setText("Invalid number.");
        }
    }

    private void fillSelectedCells(double physicalValue) {
        performEdit("set value", () -> {
            double raw = table.getDataType().quantize(table.toRaw(physicalValue));
            for (int row : getSelectedEditableModelRows()) {
                for (int col : getSelectedModelColumns()) setRawValue(row, col, raw);
            }
        });
    }

    private void fillRight() {
        performEdit("fill right", () -> {
            int[] cols = getSelectedModelColumns();
            if (cols.length < 2) return;
            for (int row : getSelectedEditableModelRows()) {
                double raw = getRawValue(row, cols[0]);
                for (int col : cols) setRawValue(row, col, raw);
            }
        });
    }

    private void fillDown() {
        performEdit("fill down", () -> {
            int[] rows = getSelectedEditableModelRows();
            if (rows.length < 2) return;
            for (int col : getSelectedModelColumns()) {
                double raw = getRawValue(rows[0], col);
                for (int row : rows) setRawValue(row, col, raw);
            }
        });
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
        if (program == null || program.isClosed()) return;
        if (!finishCellEditing() || !commitMacFields() || !dirty) return;
        int tx = program.startTransaction("Edit Denso Table: " + table.getName());
        boolean success = false;
        Exception failure = null;
        try {
            DensoTableIO.write(program, table, dataDirty, macDirty);
            success = true;
        } catch (Exception ex) {
            failure = ex;
        } finally {
            program.endTransaction(tx, success);
        }
        if (!success) {
            Msg.showError(this, this, "Write Error", "Table changes were rolled back: "
                    + failure.getMessage(), failure);
            return;
        }
        // Edits are already quantized. Reload also refreshes axes changed in the listing.
        try {
            DensoTableIO.reload(program, table);
        } catch (Exception ex) {
            statusLabel.setText("Saved, but ROM reload failed: " + ex.getMessage());
            Msg.warn(this, "Saved table could not be reloaded", ex);
            history.markSaved();
            refreshDirtyState();
            savedListener.run();
            return;
        }
        history.markSaved();
        syncMacUi();
        refreshDirtyState();
        refreshGridFromModel();
        savedListener.run();
        statusLabel.setText("Saved table changes to ROM.");
    }

    /** Updates the MAC UI fields to match the current model values. */
    private void syncMacUi() {
        if (!table.isHasMAC() || multField == null) return;
        multField.setText(String.valueOf(table.getMultiplier()));
        offField.setText(String.valueOf(table.getOffset()));
        multField.setForeground(GhidraTheme.textFieldForeground());
        offField.setForeground(GhidraTheme.textFieldForeground());
        macExprLabel.setText(table.getMacExpression());
        refreshDirtyState();
    }

    private void revertChanges() {
        if (program == null || program.isClosed() || !hasUnsavedChanges()) return;
        int choice = JOptionPane.showConfirmDialog(this,
                "Discard all unsaved changes?", "Revert",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;

        try {
            DensoTableIO.reload(program, table);
        } catch (Exception ex) {
            statusLabel.setText("Revert failed; edits retained: " + ex.getMessage());
            return;
        }
        if (grid.isEditing()) grid.getCellEditor().cancelCellEditing();
        history.reset();
        syncMacUi();
        refreshDirtyState();
        refreshGridFromModel();
        statusLabel.setText("Reverted from ROM.");
    }

    private void exportCsv() {
        if (!finishCellEditing() || !commitMacFields()) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(table.getName() + ".csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        if (fc.getSelectedFile().exists() && JOptionPane.showConfirmDialog(this,
                "Replace " + fc.getSelectedFile().getName() + "?", "Export CSV",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try (PrintWriter pw = new PrintWriter(fc.getSelectedFile(), java.nio.charset.StandardCharsets.UTF_8)) {
            if (table instanceof DensoTable2D t) {
                pw.print("Y/X");
                for (float x : t.getValuesX()) pw.print("," + Float.toString(x));
                pw.println();
                for (int r = 0; r < t.getCountY(); r++) {
                    pw.print(Float.toString(t.getValuesY()[r]));
                    for (double value : t.getValuesZ()[r]) pw.print("," + table.toPhysical(value));
                    pw.println();
                }
            } else {
                pw.print("X");
                for (float x : table.getValuesX()) pw.print("," + Float.toString(x));
                pw.println();
                pw.print("Value");
                for (double value : ((DensoTable1D) table).getValuesY()) pw.print("," + table.toPhysical(value));
                pw.println();
            }
            if (pw.checkError()) throw new IOException("Could not write the complete CSV file.");
            statusLabel.setText("Exported → " + fc.getSelectedFile().getName());
        } catch (IOException ex) {
            Msg.showError(this, this, "Export Error", ex.getMessage(), ex);
        }
    }

    public Program getProgram() { return program; }
    public long getHeaderAddress() { return table.getHeaderAddress(); }
    public DensoTable getTableSnapshot() { return table.copy(); }
    public void setSavedListener(Runnable listener) { savedListener = listener; }

    public boolean hasUnsavedChanges() {
        return dirty || (grid.isEditing() && editor.hasPendingEdit())
                || (table.isHasMAC() && multField != null
                    && (!multField.getText().trim().equals(Float.toString(table.getMultiplier()))
                        || !offField.getText().trim().equals(Float.toString(table.getOffset()))));
    }

    private void handleClose() {
        if (hasUnsavedChanges() && JOptionPane.showConfirmDialog(this,
                "You have unsaved changes. Close anyway?", "Unsaved Changes",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        dispose();
    }

    @Override
    public void dispose() {
        if (surface3DPanel != null) surface3DPanel.dispose();
        super.dispose();
    }

    private void refreshDirtyState() {
        dataDirty = history.isDataDirty();
        macDirty = history.isMacDirty();
        dirty = dataDirty || macDirty;
        boolean pending = hasUnsavedChanges();
        saveBtn.setEnabled(pending && program != null && !program.isClosed());
        revertBtn.setEnabled(pending);
        setTitle(table.getName() + " - GhidraTables" + (dirty ? " [modified]" : ""));
    }

    private void setCellValue(Object value, int row, int col) {
        if (value == null || !tableModel.isCellEditable(row, col)
                || value.toString().trim().equals(tableModel.getValueAt(row, col))) return;
        try {
            commitSelectedValue(Double.parseDouble(value.toString().trim()), new int[] {row}, new int[] {col});
        } catch (IllegalArgumentException ex) {
            statusLabel.setText(ex.getMessage());
        }
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

        @Override public void setValueAt(Object value, int row, int col) {
            setCellValue(value, row, col);
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

        @Override public void setValueAt(Object value, int row, int col) {
            setCellValue(value, row, col);
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
        int h    = Math.min(rows * 24 + 130, 950);  // less overhead since info/action bars removed
        return new Dimension(Math.max(w, 920), Math.max(h, 500));
    }
}
