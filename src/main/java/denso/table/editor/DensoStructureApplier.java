/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor;

import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;

import denso.table.editor.model.*;
import ghidra.program.model.address.*;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.Msg;
import ghidra.util.exception.CancelledException;

/**
 * Utility for applying Ghidra data type structures to the addresses of
 * discovered Denso calibration tables.
 *
 * <p>Call {@link #showDialogAndApply} to present the user with a selection
 * dialog then commit the chosen structures in a single program transaction.
 */
public final class DensoStructureApplier {

    private DensoStructureApplier() {}

    // =========================================================================
    // Options
    // =========================================================================

    public static class Options {
        public boolean applyHeader = true;
        public boolean applyXAxis  = true;
        public boolean applyYAxis  = true;
        public boolean applyData   = true;
    }

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Shows the selection dialog and, if confirmed, applies the chosen
     * structures to every table in {@code tables}.
     *
     * @return {@code true} if structures were applied, {@code false} if cancelled
     */
    public static boolean showDialogAndApply(ghidra.program.model.listing.Program program,
            List<DensoTable> tables, Component parent) {

        if (!program.getLanguage().isBigEndian()) {
            Msg.showWarn(DensoStructureApplier.class, parent,
                    "Unsupported Endianness",
                    "Apply Structure currently supports only big-endian programs. " +
                    "These Denso table headers and arrays are parsed as big-endian.");
            return false;
        }

        ApplyStructureDialog dlg = new ApplyStructureDialog(tables, parent);
        dlg.setVisible(true);
        if (!dlg.isConfirmed()) return false;

        Options opts = dlg.getOptions();
        int tx = program.startTransaction("Apply Denso Table Structures");
        boolean success = false;
        try {
            for (DensoTable t : tables) {
                applyToTable(program, t, opts);
            }
            success = true;
        } catch (Exception ex) {
            Msg.showError(DensoStructureApplier.class, parent,
                    "Structure Error", ex.getMessage(), ex);
        } finally {
            program.endTransaction(tx, success);
        }
        return success;
    }

    // =========================================================================
    // Application logic
    // =========================================================================

    private static void applyToTable(ghidra.program.model.listing.Program program,
            DensoTable table, Options opts) {

        AddressSpace space = program.getAddressFactory().getDefaultAddressSpace();
        Listing listing = program.getListing();

        if (opts.applyHeader) {
            try {
                StructureDataType hdr = buildHeaderStruct(table);
                Address addr = space.getAddress(table.getHeaderAddress());
                listing.clearCodeUnits(addr, addr.add(hdr.getLength() - 1), false);
                listing.createData(addr, hdr);
                program.getSymbolTable().createLabel(addr, table.getName(), SourceType.ANALYSIS);
            } catch (Exception ex) {
                Msg.warn(DensoStructureApplier.class,
                        "Header struct failed for " + table.getName() + ": " + ex.getMessage());
            }
        }

        if (opts.applyXAxis) {
            applyArray(program, listing, space, table.getPtrX(),
                    FloatDataType.dataType, table.getCountX(), table.getName() + "_XAxis");
        }

        if (table.is2D()) {
            DensoTable2D t2d = (DensoTable2D) table;
            if (opts.applyYAxis) {
                applyArray(program, listing, space, t2d.getPtrY(),
                        FloatDataType.dataType, t2d.getCountY(), table.getName() + "_YAxis");
            }
            if (opts.applyData) {
                applyArray(program, listing, space, t2d.getPtrZ(),
                        ghidraTypeFor(t2d.getDataType()),
                        t2d.getCountX() * t2d.getCountY(),
                        table.getName() + "_ZData");
            }
        } else {
            DensoTable1D t1d = (DensoTable1D) table;
            if (opts.applyData) {
                applyArray(program, listing, space, t1d.getPtrY(),
                        ghidraTypeFor(t1d.getDataType()),
                        t1d.getCountX(),
                        table.getName() + "_YData");
            }
        }
    }

    private static void applyArray(ghidra.program.model.listing.Program program,
            Listing listing, AddressSpace space,
            long ptr, DataType elemType, int count, String label) {
        try {
            ArrayDataType arr = new ArrayDataType(elemType, count, elemType.getLength());
            Address addr = space.getAddress(ptr);
            listing.clearCodeUnits(addr, addr.add(arr.getLength() - 1), false);
            listing.createData(addr, arr);
            program.getSymbolTable().createLabel(addr, label, SourceType.ANALYSIS);
        } catch (Exception ex) {
            Msg.warn(DensoStructureApplier.class,
                    "Array apply failed for " + label + " at 0x"
                    + Long.toHexString(ptr) + ": " + ex.getMessage());
        }
    }

    // ── Header struct builders ────────────────────────────────────────────────

    private static String headerStructName(DensoTable table) {
        if (table.is2D()) {
            return table.isHasMAC() ? "DensoTable2DHeaderMacBE" : "DensoTable2DHeaderNoMacBE";
        }
        return table.isHasMAC() ? "DensoTable1DHeaderMacBE" : "DensoTable1DHeaderNoMacBE";
    }

    private static StructureDataType buildHeaderStruct(DensoTable table) {
        String name = headerStructName(table);
        StructureDataType s = new StructureDataType(CategoryPath.ROOT, name, 0);

        Pointer32DataType ptr32 = new Pointer32DataType();
        if (table.is2D()) {
            s.add(ShortDataType.dataType,  2, "countX",    "Number of X-axis entries");
            s.add(ShortDataType.dataType,  2, "countY",    "Number of Y-axis entries");
            s.add(ptr32,                   4, "ptrX",      "Pointer to X-axis float array");
            s.add(ptr32,                   4, "ptrY",      "Pointer to Y-axis float array");
            s.add(ptr32,                   4, "ptrZ",      "Pointer to Z-data array");
            s.add(DWordDataType.dataType,  4, "tableType", "Data type code (little-endian)");
        } else {
            s.add(ShortDataType.dataType,  2, "countX",    "Number of entries");
            s.add(ShortDataType.dataType,  2, "tableType", "Data type code (little-endian)");
            s.add(ptr32,                   4, "ptrX",      "Pointer to X-axis float array");
            s.add(ptr32,                   4, "ptrY",      "Pointer to Y-data array");
        }

        if (table.isHasMAC()) {
            s.add(FloatDataType.dataType, 4, "multiplier", "MAC scale factor");
            s.add(FloatDataType.dataType, 4, "offset",     "MAC additive offset");
        }

        return s;
    }

    // ── DensoTableType → Ghidra DataType ─────────────────────────────────────

    static DataType ghidraTypeFor(DensoTableType dt) {
        switch (dt) {
            case FLOAT:  return FloatDataType.dataType;
            case UINT8:  return ByteDataType.dataType;
            case UINT16: return WordDataType.dataType;
            case INT8:   return CharDataType.dataType;
            case INT16:  return ShortDataType.dataType;
            case UINT32: return DWordDataType.dataType;
            default:     return ByteDataType.dataType;
        }
    }

    /** Human-readable description of the Ghidra type for a DensoTableType. */
    static String ghidraTypeNameFor(DensoTableType dt) {
        switch (dt) {
            case FLOAT:  return "float";
            case UINT8:  return "byte";
            case UINT16: return "word";
            case INT8:   return "char";
            case INT16:  return "short";
            case UINT32: return "dword";
            default:     return "byte";
        }
    }

    // =========================================================================
    // Dialog
    // =========================================================================

    /**
     * Modal dialog that lets the user choose which structure types to apply
     * and shows a preview of every address that will be written.
     */
    public static final class ApplyStructureDialog extends JDialog {
        private final List<DensoTable> tables;
        private final boolean hasAny2D;

        private final JCheckBox cbHeader = new JCheckBox("Header struct", true);
        private final JCheckBox cbXAxis  = new JCheckBox("X axis arrays", true);
        private final JCheckBox cbYAxis  = new JCheckBox("Y axis arrays (2D only)", true);
        private final JCheckBox cbData   = new JCheckBox("Value/Z data arrays", true);
        private final PreviewTableModel previewModel = new PreviewTableModel();
        private final JTable previewTable = new JTable(previewModel);
        private final JLabel previewSummaryLabel = new JLabel();
        private JButton applyButton;

        private boolean confirmed = false;

        public ApplyStructureDialog(List<DensoTable> tables, Component parent) {
            super(SwingUtilities.getWindowAncestor(parent),
                    "Apply Table Structures", ModalityType.APPLICATION_MODAL);
            this.tables = List.copyOf(tables);
            this.hasAny2D = tables.stream().anyMatch(DensoTable::is2D);

            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            setLayout(new BorderLayout());
            add(buildHeader(),  BorderLayout.NORTH);
            add(buildCenter(),  BorderLayout.CENTER);
            add(buildButtons(), BorderLayout.SOUTH);

            updatePreview();
            pack();
            setMinimumSize(new Dimension(820, 520));
            setSize(Math.max(getWidth(), 980), Math.max(getHeight(), 620));
            setResizable(true);
            getRootPane().setDefaultButton(applyButton);
            getRootPane().registerKeyboardAction(e -> dispose(),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                    JComponent.WHEN_IN_FOCUSED_WINDOW);
            if (parent != null) setLocationRelativeTo(parent);
        }

        public boolean isConfirmed() { return confirmed; }

        public Options getOptions() {
            Options o = new Options();
            o.applyHeader = cbHeader.isSelected();
            o.applyXAxis  = cbXAxis.isSelected();
            o.applyYAxis  = cbYAxis.isEnabled() && cbYAxis.isSelected();
            o.applyData   = cbData.isSelected();
            return o;
        }

        // ── UI construction ───────────────────────────────────────────────────

        private JComponent buildHeader() {
            JPanel panel = new JPanel(new BorderLayout(12, 0));
            panel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor()),
                    BorderFactory.createEmptyBorder(14, 16, 12, 16)));

            JLabel iconLabel = new JLabel(UIManager.getIcon("OptionPane.informationIcon"));
            iconLabel.setVerticalAlignment(SwingConstants.TOP);

            JPanel text = new JPanel();
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));

            JLabel title = new JLabel("Apply structures to " + tables.size()
                    + " table" + (tables.size() == 1 ? "" : "s"));
            title.setFont(titleFont());
            title.setAlignmentX(0f);

            JTextArea note = makeWrappedText(
                    "Existing code units in each selected range will be cleared before " +
                    "the new header and array data types are created.",
                    smallFont(), secondaryForeground());

            text.add(title);
            text.add(Box.createVerticalStrut(6));
            text.add(note);

            panel.add(iconLabel, BorderLayout.WEST);
            panel.add(text, BorderLayout.CENTER);
            return panel;
        }

        private JComponent buildCenter() {
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                    buildOptionsPanel(), buildPreviewPanel());
            split.setBorder(BorderFactory.createEmptyBorder());
            split.setResizeWeight(0.0);
            split.setDividerLocation(310);
            split.setContinuousLayout(true);
            split.setOneTouchExpandable(true);
            return split;
        }

        private JComponent buildOptionsPanel() {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 12));

            JLabel section = new JLabel("What to apply");
            section.setFont(titleFont().deriveFont(titleFont().getSize2D() - 1f));
            section.setAlignmentX(0f);
            panel.add(section);
            panel.add(Box.createVerticalStrut(10));

            panel.add(buildOptionRow(cbHeader,
                    "Create the Denso header structure and label it at the table address."));
            panel.add(Box.createVerticalStrut(8));
            panel.add(buildOptionRow(cbXAxis,
                    "Create the float array for the X-axis breakpoints."));
            panel.add(Box.createVerticalStrut(8));

            cbYAxis.setEnabled(hasAny2D);
            if (!hasAny2D) {
                cbYAxis.setSelected(false);
            }
            panel.add(buildOptionRow(cbYAxis,
                    hasAny2D
                        ? "Create the float array for the Y-axis breakpoints on 2D tables."
                        : "No 2D tables are selected, so there is no Y-axis array to apply."));
            panel.add(Box.createVerticalStrut(8));
            panel.add(buildOptionRow(cbData,
                    "Create the value array for 1D tables or the Z-data matrix for 2D tables."));
            panel.add(Box.createVerticalStrut(14));

            JSeparator separator = new JSeparator();
            separator.setAlignmentX(0f);
            panel.add(separator);
            panel.add(Box.createVerticalStrut(12));

            JLabel previewTitle = new JLabel("Selection summary");
            previewTitle.setFont(labelFont().deriveFont(Font.BOLD));
            previewTitle.setAlignmentX(0f);
            panel.add(previewTitle);
            panel.add(Box.createVerticalStrut(6));

            previewSummaryLabel.setFont(smallFont());
            previewSummaryLabel.setForeground(secondaryForeground());
            previewSummaryLabel.setAlignmentX(0f);
            panel.add(previewSummaryLabel);
            panel.add(Box.createVerticalStrut(12));
            panel.add(makeWrappedText(
                    "Preview rows are generated directly from the current checkbox selection. " +
                    "If a range looks wrong, cancel and rescan instead of applying blindly.",
                    smallFont(), secondaryForeground()));
            panel.add(Box.createVerticalGlue());
            return panel;
        }

        private JComponent buildOptionRow(JCheckBox checkBox, String description) {
            checkBox.setFont(labelFont());
            checkBox.setFocusPainted(false);
            checkBox.setAlignmentX(0f);
            checkBox.addActionListener(e -> updatePreview());

            JTextArea descriptionArea = makeWrappedText(description, smallFont(),
                    checkBox.isEnabled() ? secondaryForeground() : disabledForeground());
            descriptionArea.setAlignmentX(0f);

            JPanel row = new JPanel();
            row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
            row.setAlignmentX(0f);
            row.add(checkBox);
            row.add(Box.createVerticalStrut(2));
            row.add(descriptionArea);
            return row;
        }

        private JComponent buildPreviewPanel() {
            previewTable.setFillsViewportHeight(true);
            previewTable.setRowHeight(24);
            previewTable.setFont(labelFont());
            previewTable.getTableHeader().setFont(labelFont().deriveFont(Font.BOLD));
            previewTable.setAutoCreateRowSorter(true);
            previewTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            previewTable.setDefaultEditor(Object.class, null);

            JScrollPane scroll = new JScrollPane(previewTable);
            scroll.setBorder(BorderFactory.createEmptyBorder());

            JPanel panel = new JPanel(new BorderLayout(0, 10));
            panel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 1, 0, 0, borderColor()),
                    BorderFactory.createEmptyBorder(16, 16, 16, 16)));

            JLabel title = new JLabel("Preview");
            title.setFont(titleFont().deriveFont(titleFont().getSize2D() - 1f));

            JTextArea hint = makeWrappedText(
                    "Each row below is one range that will be cleared and recreated.",
                    smallFont(), secondaryForeground());

            JPanel top = new JPanel();
            top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
            top.add(title);
            top.add(Box.createVerticalStrut(6));
            top.add(hint);

            panel.add(top, BorderLayout.NORTH);
            panel.add(scroll, BorderLayout.CENTER);
            return panel;
        }

        private JComponent buildButtons() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, borderColor()),
                    BorderFactory.createEmptyBorder(10, 16, 10, 16)));

            JTextArea note = makeWrappedText(
                    "Apply Structure updates the listing only; it does not modify ROM bytes.",
                    smallFont(), secondaryForeground());
            panel.add(note, BorderLayout.CENTER);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            JButton cancel = new JButton("Cancel");
            applyButton = new JButton("Apply");

            cancel.addActionListener(e -> {
                confirmed = false;
                dispose();
            });
            applyButton.addActionListener(e -> {
                confirmed = true;
                dispose();
            });

            buttons.add(cancel);
            buttons.add(applyButton);
            panel.add(buttons, BorderLayout.EAST);
            return panel;
        }

        // ── Preview ───────────────────────────────────────────────────────────

        private void updatePreview() {
            List<PreviewRow> rows = buildPreviewRows();
            previewModel.setRows(rows);

            int totalBytes = rows.stream().mapToInt(r -> r.bytes).sum();
            previewSummaryLabel.setText(String.format(
                    "%d range%s selected across %d table%s (%d bytes total).",
                    rows.size(),
                    rows.size() == 1 ? "" : "s",
                    tables.size(),
                    tables.size() == 1 ? "" : "s",
                    totalBytes));
            applyButton.setEnabled(!rows.isEmpty());

            if (previewTable.getColumnModel().getColumnCount() == 5) {
                previewTable.getColumnModel().getColumn(0).setPreferredWidth(220);
                previewTable.getColumnModel().getColumn(1).setPreferredWidth(90);
                previewTable.getColumnModel().getColumn(2).setPreferredWidth(110);
                previewTable.getColumnModel().getColumn(3).setPreferredWidth(160);
                previewTable.getColumnModel().getColumn(4).setPreferredWidth(80);
            }
        }

        private static int headerSize(DensoTable t) {
            int base = t.is2D() ? 20 : 12;
            return t.isHasMAC() ? base + 8 : base;
        }

        // ── Helpers ───────────────────────────────────────────────────────────

        private List<PreviewRow> buildPreviewRows() {
            List<PreviewRow> rows = new ArrayList<>();
            for (DensoTable t : tables) {
                if (cbHeader.isSelected()) {
                    String headerName = headerStructName(t);
                    rows.add(new PreviewRow(
                            t.getName(),
                            "Header",
                            t.getAddressHex(),
                            headerName,
                            headerSize(t)));
                }
                if (cbXAxis.isSelected()) {
                    rows.add(new PreviewRow(
                            t.getName(),
                            "X Axis",
                            String.format("0x%X", t.getPtrX()),
                            String.format("float[%d]", t.getCountX()),
                            t.getCountX() * Float.BYTES));
                }
                if (t.is2D()) {
                    DensoTable2D t2d = (DensoTable2D) t;
                    if (cbYAxis.isEnabled() && cbYAxis.isSelected()) {
                        rows.add(new PreviewRow(
                                t.getName(),
                                "Y Axis",
                                String.format("0x%X", t2d.getPtrY()),
                                String.format("float[%d]", t2d.getCountY()),
                                t2d.getCountY() * Float.BYTES));
                    }
                    if (cbData.isSelected()) {
                        int count = t2d.getCountX() * t2d.getCountY();
                        rows.add(new PreviewRow(
                                t.getName(),
                                "Z Data",
                                String.format("0x%X", t2d.getPtrZ()),
                                String.format("%s[%d]", ghidraTypeNameFor(t2d.getDataType()), count),
                                count * t2d.getDataType().getValueSize()));
                    }
                }
                else if (cbData.isSelected()) {
                    DensoTable1D t1d = (DensoTable1D) t;
                    rows.add(new PreviewRow(
                            t.getName(),
                            "Y Data",
                            String.format("0x%X", t1d.getPtrY()),
                            String.format("%s[%d]", ghidraTypeNameFor(t1d.getDataType()), t1d.getCountX()),
                            t1d.getCountX() * t1d.getDataType().getValueSize()));
                }
            }
            return rows;
        }

        private JTextArea makeWrappedText(String text, Font font, Color color) {
            JTextArea area = new JTextArea(text);
            area.setEditable(false);
            area.setFocusable(false);
            area.setOpaque(false);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setFont(font);
            area.setForeground(color);
            area.setBorder(BorderFactory.createEmptyBorder());
            area.setAlignmentX(0f);
            return area;
        }

        private Font labelFont() {
            Font font = UIManager.getFont("Label.font");
            return font != null ? font : new JLabel().getFont();
        }

        private Font smallFont() {
            Font base = labelFont();
            return base.deriveFont(Math.max(11f, base.getSize2D() - 1f));
        }

        private Font titleFont() {
            Font base = labelFont();
            return base.deriveFont(Font.BOLD, base.getSize2D() + 2f);
        }

        private Color borderColor() {
            Color c = UIManager.getColor("Separator.foreground");
            if (c == null) c = UIManager.getColor("controlShadow");
            return c != null ? c : Color.GRAY;
        }

        private Color secondaryForeground() {
            Color c = UIManager.getColor("Label.disabledForeground");
            if (c == null) c = UIManager.getColor("Label.foreground");
            return c != null ? c : Color.GRAY;
        }

        private Color disabledForeground() {
            Color c = UIManager.getColor("CheckBox.disabledText");
            if (c == null) c = secondaryForeground();
            return c;
        }

        private static final class PreviewRow {
            final String table;
            final String target;
            final String address;
            final String creates;
            final int bytes;

            PreviewRow(String table, String target, String address, String creates, int bytes) {
                this.table = table;
                this.target = target;
                this.address = address;
                this.creates = creates;
                this.bytes = bytes;
            }
        }

        private static final class PreviewTableModel extends AbstractTableModel {
            private static final String[] COLUMNS = {"Table", "Target", "Address", "Creates", "Bytes"};
            private List<PreviewRow> rows = List.of();

            void setRows(List<PreviewRow> rows) {
                this.rows = List.copyOf(rows);
                fireTableDataChanged();
            }

            @Override
            public int getRowCount() {
                return rows.size();
            }

            @Override
            public int getColumnCount() {
                return COLUMNS.length;
            }

            @Override
            public String getColumnName(int column) {
                return COLUMNS[column];
            }

            @Override
            public Object getValueAt(int rowIndex, int columnIndex) {
                PreviewRow row = rows.get(rowIndex);
                return switch (columnIndex) {
                    case 0 -> row.table;
                    case 1 -> row.target;
                    case 2 -> row.address;
                    case 3 -> row.creates;
                    case 4 -> row.bytes;
                    default -> "";
                };
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 4 ? Integer.class : String.class;
            }
        }
    }
}
