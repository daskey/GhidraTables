/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor;

import java.awt.*;
import java.awt.event.*;
import java.util.List;
import javax.swing.*;

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

    private static StructureDataType buildHeaderStruct(DensoTable table) {
        String name = table.is2D() ? "DensoTable2DHeaderBE" : "DensoTable1DHeaderBE";
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

        private static final Color BG       = new Color(28, 36, 48);
        private static final Color FG       = new Color(190, 210, 230);
        private static final Color DIM      = new Color(110, 135, 165);
        private static final Color PREVIEW  = new Color(22, 30, 42);
        private static final Color ACCENT   = new Color(60, 130, 220);

        private final List<DensoTable> tables;

        private final JCheckBox cbHeader = new JCheckBox("Header struct", true);
        private final JCheckBox cbXAxis  = new JCheckBox("X axis  (float[])", true);
        private final JCheckBox cbYAxis  = new JCheckBox("Y axis  (float[] or data[])", true);
        private final JCheckBox cbData   = new JCheckBox("Data array", true);
        private final JTextArea preview  = new JTextArea();

        private boolean confirmed = false;

        public ApplyStructureDialog(List<DensoTable> tables, Component parent) {
            super(SwingUtilities.getWindowAncestor(parent),
                    "Apply Table Structures", ModalityType.APPLICATION_MODAL);
            this.tables = tables;

            setLayout(new BorderLayout(0, 0));
            getContentPane().setBackground(BG);

            add(buildHeader(),  BorderLayout.NORTH);
            add(buildCenter(),  BorderLayout.CENTER);
            add(buildButtons(), BorderLayout.SOUTH);

            updatePreview();
            pack();
            setMinimumSize(new Dimension(540, 420));
            setResizable(true);
            if (parent != null) setLocationRelativeTo(parent);
        }

        public boolean isConfirmed() { return confirmed; }

        public Options getOptions() {
            Options o = new Options();
            o.applyHeader = cbHeader.isSelected();
            o.applyXAxis  = cbXAxis.isSelected();
            o.applyYAxis  = cbYAxis.isSelected();
            o.applyData   = cbData.isSelected();
            return o;
        }

        // ── UI construction ───────────────────────────────────────────────────

        private JPanel buildHeader() {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 10));
            p.setBackground(new Color(35, 44, 58));
            p.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(50, 65, 85)));
            JLabel lbl = new JLabel("Apply structures to " + tables.size()
                    + " table" + (tables.size() == 1 ? "" : "s"));
            lbl.setForeground(FG);
            lbl.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
            p.add(lbl);

            JLabel note = new JLabel("Clears existing code units across the selected ranges.");
            note.setForeground(DIM);
            note.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
            p.add(note);
            return p;
        }

        private JSplitPane buildCenter() {
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                    buildCheckboxPanel(), buildPreviewPanel());
            split.setBackground(BG);
            split.setDividerLocation(210);
            split.setBorder(null);
            return split;
        }

        private JPanel buildCheckboxPanel() {
            JPanel p = new JPanel();
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setBackground(BG);
            p.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 8));

            JLabel lbl = new JLabel("Structure types:");
            lbl.setForeground(DIM);
            lbl.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            lbl.setAlignmentX(0f);
            p.add(lbl);
            p.add(Box.createVerticalStrut(8));

            for (JCheckBox cb : new JCheckBox[]{cbHeader, cbXAxis, cbYAxis, cbData}) {
                style(cb);
                cb.addItemListener(e -> updatePreview());
                p.add(cb);
                p.add(Box.createVerticalStrut(4));
            }
            return p;
        }

        private JPanel buildPreviewPanel() {
            preview.setEditable(false);
            preview.setBackground(PREVIEW);
            preview.setForeground(new Color(160, 195, 160));
            preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            preview.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

            JScrollPane scroll = new JScrollPane(preview);
            scroll.setBackground(PREVIEW);
            scroll.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(50, 65, 85)));

            JPanel p = new JPanel(new BorderLayout());
            p.setBackground(BG);
            JLabel lbl = new JLabel("  Preview");
            lbl.setForeground(DIM);
            lbl.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            lbl.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 0));
            p.add(lbl,    BorderLayout.NORTH);
            p.add(scroll, BorderLayout.CENTER);
            return p;
        }

        private JPanel buildButtons() {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
            p.setBackground(new Color(22, 30, 42));
            p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(50, 65, 85)));

            JButton apply  = makeBtn("Apply",  ACCENT);
            JButton cancel = makeBtn("Cancel", new Color(70, 40, 40));

            apply.addActionListener(e  -> { confirmed = true;  dispose(); });
            cancel.addActionListener(e -> { confirmed = false; dispose(); });

            p.add(cancel);
            p.add(apply);
            return p;
        }

        // ── Preview ───────────────────────────────────────────────────────────

        private void updatePreview() {
            StringBuilder sb = new StringBuilder();
            for (DensoTable t : tables) {
                sb.append("── ").append(t.getName()).append(" ──\n");

                if (cbHeader.isSelected()) {
                    String hdrName = t.is2D() ? "DensoTable2DHeaderBE" : "DensoTable1DHeaderBE";
                    int hdrSize = headerSize(t);
                    sb.append(String.format("  %-20s %s  (%d bytes)\n",
                            hdrName, t.getAddressHex(), hdrSize));
                }
                if (cbXAxis.isSelected()) {
                    sb.append(String.format("  %-20s 0x%X  (float[%d])\n",
                            "X axis", t.getPtrX(), t.getCountX()));
                }
                if (t.is2D()) {
                    DensoTable2D t2d = (DensoTable2D) t;
                    if (cbYAxis.isSelected()) {
                        sb.append(String.format("  %-20s 0x%X  (float[%d])\n",
                                "Y axis", t2d.getPtrY(), t2d.getCountY()));
                    }
                    if (cbData.isSelected()) {
                        String typeName = ghidraTypeNameFor(t2d.getDataType());
                        sb.append(String.format("  %-20s 0x%X  (%s[%d])\n",
                                "Z data", t2d.getPtrZ(), typeName,
                                t2d.getCountX() * t2d.getCountY()));
                    }
                } else {
                    DensoTable1D t1d = (DensoTable1D) t;
                    if (cbData.isSelected()) {
                        String typeName = ghidraTypeNameFor(t1d.getDataType());
                        sb.append(String.format("  %-20s 0x%X  (%s[%d])\n",
                                "Y data", t1d.getPtrY(), typeName, t1d.getCountX()));
                    }
                }
                sb.append('\n');
            }
            preview.setText(sb.toString());
            preview.setCaretPosition(0);
        }

        private static int headerSize(DensoTable t) {
            int base = t.is2D() ? 20 : 12;
            return t.isHasMAC() ? base + 8 : base;
        }

        // ── Helpers ───────────────────────────────────────────────────────────

        private void style(JCheckBox cb) {
            cb.setBackground(BG);
            cb.setForeground(FG);
            cb.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            cb.setFocusPainted(false);
            cb.setAlignmentX(0f);
        }

        private JButton makeBtn(String text, Color bg) {
            JButton b = new JButton(text) {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(getModel().isRollover() ? bg.brighter() : bg);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            b.setForeground(Color.WHITE);
            b.setFont(new Font("Dialog", Font.BOLD, 12));
            b.setPreferredSize(new Dimension(80, 28));
            b.setContentAreaFilled(false);
            b.setBorderPainted(false);
            b.setFocusPainted(false);
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            return b;
        }
    }
}
