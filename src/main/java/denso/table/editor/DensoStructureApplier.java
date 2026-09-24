/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor;

import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;

import denso.table.editor.model.*;
import ghidra.program.model.address.*;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.Msg;

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
        List<String> failures = new ArrayList<>();
        int tx = program.startTransaction("Apply Denso Table Structures");
        boolean success = false;
        try {
            applyAll(program, tables, opts, failures);
            success = true;
        } catch (Exception ex) {
            Msg.showError(DensoStructureApplier.class, parent,
                    "Structure Error", ex.getMessage(), ex);
        } finally {
            program.endTransaction(tx, success);
        }

        if (success && !failures.isEmpty()) {
            showFailureSummary(parent, failures);
        }
        return success;
    }

    private static void showFailureSummary(Component parent, List<String> failures) {
        final int maxListed = 10;
        StringBuilder sb = new StringBuilder();
        sb.append(failures.size()).append(" range")
          .append(failures.size() == 1 ? "" : "s")
          .append(" could not be marked up:\n\n");
        for (int i = 0; i < Math.min(maxListed, failures.size()); i++) {
            sb.append("• ").append(failures.get(i)).append('\n');
        }
        if (failures.size() > maxListed) {
            sb.append("… and ").append(failures.size() - maxListed)
              .append(" more (see the Ghidra log).");
        }
        Msg.showWarn(DensoStructureApplier.class, parent,
                "Some Structures Were Not Applied", sb.toString().trim());
    }

    // =========================================================================
    // Application logic
    // =========================================================================

    /** One array a table asks for. Overlapping requests of the same type are merged. */
    private record ArrayRequest(long start, DataType elemType, int count, String label) {
        long end() {
            return start + (long) count * elemType.getLength() - 1;
        }
    }

    /**
     * Applies header structures, then axis and data arrays, for every table.
     *
     * <p>Axis arrays are often shared between tables, and one table's axis can
     * start inside another's (for example an 8-point axis that reuses the first
     * points of a 16-point one). Applying arrays one table at a time let each
     * clear the previous one, so shared axes were truncated or left undefined.
     * Instead, overlapping requests of the same element type are merged into a
     * single array covering all of them, existing arrays of that type are
     * extended rather than cut down, and each table still gets a label at its
     * own start address.
     */
    static void applyAll(Program program, List<DensoTable> tables, Options opts,
            List<String> failures) {
        AddressSpace space = program.getAddressFactory().getDefaultAddressSpace();
        AddressSet created = new AddressSet();
        List<ArrayRequest> requests = new ArrayList<>();

        for (DensoTable table : tables) {
            if (opts.applyHeader) {
                applyHeader(program, space, table, created, failures);
            }
            collectArrayRequests(table, opts, requests);
        }

        for (List<ArrayRequest> group : groupOverlappingRequests(requests)) {
            applyArrayGroup(program, space, group, created, failures);
        }
    }

    private static void applyHeader(Program program, AddressSpace space, DensoTable table,
            AddressSet created, List<String> failures) {
        try {
            StructureDataType hdr = buildHeaderStruct(table);
            Address addr = space.getAddress(table.getHeaderAddress());
            Address end = addr.add(hdr.getLength() - 1);
            Listing listing = program.getListing();
            listing.clearCodeUnits(addr, end, false);
            listing.createData(addr, hdr);
            created.add(addr, end);
            program.getSymbolTable().createLabel(addr, table.getName(), SourceType.ANALYSIS);
        } catch (Exception ex) {
            String failure = table.getName() + " header at " + table.getAddressHex()
                    + ": " + ex.getMessage();
            Msg.warn(DensoStructureApplier.class, "Header struct failed for " + failure);
            failures.add(failure);
        }
    }

    private static void collectArrayRequests(DensoTable table, Options opts,
            List<ArrayRequest> requests) {
        if (opts.applyXAxis) {
            requests.add(new ArrayRequest(table.getPtrX(), FloatDataType.dataType,
                    table.getCountX(), table.getName() + "_XAxis"));
        }
        if (table.is2D()) {
            DensoTable2D t2d = (DensoTable2D) table;
            if (opts.applyYAxis) {
                requests.add(new ArrayRequest(t2d.getPtrY(), FloatDataType.dataType,
                        t2d.getCountY(), table.getName() + "_YAxis"));
            }
            if (opts.applyData) {
                requests.add(new ArrayRequest(t2d.getPtrZ(), ghidraTypeFor(t2d.getDataType()),
                        t2d.getCountX() * t2d.getCountY(), table.getName() + "_ZData"));
            }
        }
        else if (opts.applyData) {
            DensoTable1D t1d = (DensoTable1D) table;
            requests.add(new ArrayRequest(t1d.getPtrY(), ghidraTypeFor(t1d.getDataType()),
                    t1d.getCountX(), table.getName() + "_YData"));
        }
    }

    /**
     * Groups requests that share an element type and overlap on element
     * boundaries. Groups are returned in address order.
     */
    private static List<List<ArrayRequest>> groupOverlappingRequests(List<ArrayRequest> requests) {
        Map<String, List<ArrayRequest>> byType = new LinkedHashMap<>();
        for (ArrayRequest r : requests) {
            byType.computeIfAbsent(r.elemType().getName(), k -> new ArrayList<>()).add(r);
        }

        List<List<ArrayRequest>> groups = new ArrayList<>();
        for (List<ArrayRequest> sameType : byType.values()) {
            sameType.sort(Comparator.comparingLong(ArrayRequest::start));
            List<ArrayRequest> group = null;
            long groupEnd = Long.MIN_VALUE;
            for (ArrayRequest r : sameType) {
                int elemLen = r.elemType().getLength();
                boolean joins = group != null && r.start() <= groupEnd
                        && (r.start() - group.get(0).start()) % elemLen == 0;
                if (joins) {
                    group.add(r);
                    groupEnd = Math.max(groupEnd, r.end());
                }
                else {
                    group = new ArrayList<>();
                    group.add(r);
                    groups.add(group);
                    groupEnd = r.end();
                }
            }
        }
        groups.sort(Comparator.comparingLong(g -> g.get(0).start()));
        return groups;
    }

    private static void applyArrayGroup(Program program, AddressSpace space,
            List<ArrayRequest> group, AddressSet created, List<String> failures) {
        Listing listing = program.getListing();
        DataType elemType = group.get(0).elemType();
        int elemLen = elemType.getLength();
        long start = group.stream().mapToLong(ArrayRequest::start).min().getAsLong();
        long end = group.stream().mapToLong(ArrayRequest::end).max().getAsLong();
        String labels = String.join(", ", group.stream().map(ArrayRequest::label).toList());

        try {
            // Grow over existing arrays of the same element type (e.g. from an
            // earlier Apply) so re-applying a subset never truncates them.
            boolean grew = true;
            while (grew) {
                grew = false;
                for (Data d : definedDataIn(listing, space.getAddress(start), space.getAddress(end))) {
                    if (!isArrayOf(d, elemType)
                            || (d.getAddress().getOffset() - start) % elemLen != 0) {
                        continue;
                    }
                    long dStart = d.getAddress().getOffset();
                    long dEnd = d.getMaxAddress().getOffset();
                    if (dStart < start || dEnd > end) {
                        start = Math.min(start, dStart);
                        end = Math.max(end, dEnd);
                        grew = true;
                    }
                }
            }

            Address startAddr = space.getAddress(start);
            Address endAddr = space.getAddress(end);
            if (created.intersects(startAddr, endAddr)) {
                String failure = String.format("%s at 0x%X: overlaps a header or an array of a " +
                        "different type applied in this batch; left unchanged", labels, start);
                Msg.warn(DensoStructureApplier.class, failure);
                failures.add(failure);
            }
            else {
                Data existing = listing.getDefinedDataAt(startAddr);
                boolean alreadyApplied = existing != null && isArrayOf(existing, elemType)
                        && existing.getLength() == end - start + 1;
                if (!alreadyApplied) {
                    int count = (int) ((end - start + 1) / elemLen);
                    listing.clearCodeUnits(startAddr, endAddr, false);
                    listing.createData(startAddr, new ArrayDataType(elemType, count, elemLen));
                }
                created.add(startAddr, endAddr);
            }

            for (ArrayRequest r : group) {
                program.getSymbolTable().createLabel(space.getAddress(r.start()), r.label(),
                        SourceType.ANALYSIS);
            }
        } catch (Exception ex) {
            String failure = String.format("%s at 0x%X: %s", labels, start, ex.getMessage());
            Msg.warn(DensoStructureApplier.class, "Array apply failed for " + failure);
            failures.add(failure);
        }
    }

    /** Defined data that starts in, or contains the start of, [start, end]. */
    private static List<Data> definedDataIn(Listing listing, Address start, Address end) {
        List<Data> result = new ArrayList<>();
        Data containing = listing.getDefinedDataContaining(start);
        if (containing != null) {
            result.add(containing);
        }
        for (Data d : listing.getDefinedData(new AddressSet(start, end), true)) {
            if (containing == null || !d.getAddress().equals(containing.getAddress())) {
                result.add(d);
            }
        }
        return result;
    }

    private static boolean isArrayOf(Data data, DataType elemType) {
        return data.getDataType() instanceof Array array
                && array.getDataType().isEquivalent(elemType);
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
            case INT8:   return SignedByteDataType.dataType;
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
            case INT8:   return "sbyte";
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
            super(ownerWindowFor(parent),
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

        /**
         * Resolves the dialog owner. {@link SwingUtilities#getWindowAncestor} returns
         * the <em>parent</em> of a window (null for a top-level frame), so a window
         * passed directly must be used as-is or the dialog ends up unowned.
         */
        private static Window ownerWindowFor(Component parent) {
            if (parent instanceof Window window) {
                return window;
            }
            return parent != null ? SwingUtilities.getWindowAncestor(parent) : null;
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
                    "Each row below is one range that will be cleared and recreated. " +
                    "Overlapping arrays of the same type, such as shared axes, become " +
                    "one array and each table keeps its own label.",
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
