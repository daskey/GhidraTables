/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor.ui;

import java.awt.*;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;

import generic.theme.Gui;

/**
 * A {@link javax.swing.table.TableCellRenderer} that colours data cells using
 * a blue→cyan→green→yellow→red heat-map gradient, and renders axis header
 * cells using the active Ghidra theme.
 *
 * <p>Cell types:
 * <ul>
 *   <li><b>CORNER</b> – top-left label cell ("Y\\X").</li>
 *   <li><b>X_HEADER</b> – top row (X-axis values), col > 0.</li>
 *   <li><b>Y_HEADER</b> – leftmost column (Y-axis values), row > 0.</li>
 *   <li><b>DATA</b>     – the editable value cells.</li>
 *   <li><b>DATA_2D</b>  – X-axis row in the 2-D view (row 0).</li>
 * </ul>
 */
public class HeatMapCellRenderer extends DefaultTableCellRenderer {

    public enum CellRole { CORNER, X_HEADER, Y_HEADER, DATA }

    private double globalMin = 0;
    private double globalMax = 1;

    // ── Public configuration ──────────────────────────────────────────────────

    public void setRange(double min, double max) {
        this.globalMin = min;
        this.globalMax = (max == min) ? min + 1 : max;  // avoid division by zero
    }

    // ── Core rendering ────────────────────────────────────────────────────────

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
            boolean isSelected, boolean hasFocus, int row, int col) {

        JLabel label = (JLabel) super.getTableCellRendererComponent(
                table, value, false, false, row, col);

        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));

        CellRole role = getRoleFor(row, col);

        switch (role) {
            case CORNER -> {
                label.setBackground(GhidraTheme.surfaceBackground());
                label.setForeground(GhidraTheme.secondaryForeground());
                label.setFont(label.getFont().deriveFont(Font.BOLD | Font.ITALIC));
            }
            case X_HEADER, Y_HEADER -> {
                label.setBackground(GhidraTheme.tableHeaderBackground());
                label.setForeground(GhidraTheme.tableHeaderForeground());
                label.setFont(label.getFont().deriveFont(Font.BOLD));
            }
            case DATA -> {
                double numeric = parseDouble(value);
                double norm    = normalize(numeric);
                Color  bg      = heatColor(norm);
                Color  selected = GhidraTheme.tableSelectionBackground();
                label.setBackground(isSelected ? blend(bg, selected, 0.35f) : bg);
                label.setForeground(isDark(bg) ? Color.WHITE : Color.BLACK);
                label.setFont(label.getFont().deriveFont(Font.PLAIN));
            }
        }

        label.setOpaque(true);
        return label;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Override in the table's column model to specify the role for each cell. */
    protected CellRole getRoleFor(int row, int col) {
        if (row == 0 && col == 0) return CellRole.CORNER;
        if (row == 0)             return CellRole.X_HEADER;
        if (col == 0)             return CellRole.Y_HEADER;
        return CellRole.DATA;
    }

    private double normalize(double value) {
        double n = (value - globalMin) / (globalMax - globalMin);
        return Math.max(0, Math.min(1, n));
    }

    private static double parseDouble(Object value) {
        if (value instanceof Number n)  return n.doubleValue();
        if (value == null)              return 0;
        try { return Double.parseDouble(value.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    /**
     * Maps a normalised value [0,1] to a blue → cyan → green → yellow → red
     * heat-map colour, adjusted for the current dark/light theme.
     */
    public static Color heatColor(double t) {
        t = Math.max(0, Math.min(1, t));

        int r, g, b;
        if (t < 0.25) {
            double s = t / 0.25;
            r = 0;
            g = (int) (255 * s);
            b = 255;
        } else if (t < 0.5) {
            double s = (t - 0.25) / 0.25;
            r = 0;
            g = 255;
            b = (int) (255 * (1 - s));
        } else if (t < 0.75) {
            double s = (t - 0.5) / 0.25;
            r = (int) (255 * s);
            g = 255;
            b = 0;
        } else {
            double s = (t - 0.75) / 0.25;
            r = 255;
            g = (int) (255 * (1 - s));
            b = 0;
        }
        Color raw = new Color(r, g, b);

        // Adjust for dark themes: slightly desaturate and brighten to avoid
        // washing out against dark backgrounds
        if (Gui.isDarkTheme()) {
            Color bg = GhidraTheme.tableBackground();
            return blend(raw, bg, 0.12f);
        }
        return raw;
    }

    /** Returns true if the colour is perceived as dark. */
    private static boolean isDark(Color c) {
        double lum = 0.2126 * c.getRed() / 255.0
                   + 0.7152 * c.getGreen() / 255.0
                   + 0.0722 * c.getBlue() / 255.0;
        return lum < 0.45;
    }

    /** Blends two colours. {@code t} = weight of {@code b}. */
    private static Color blend(Color a, Color b, float t) {
        float s = 1 - t;
        int r = Math.min(255, (int) (a.getRed()   * s + b.getRed()   * t));
        int g = Math.min(255, (int) (a.getGreen() * s + b.getGreen() * t));
        int bv= Math.min(255, (int) (a.getBlue()  * s + b.getBlue()  * t));
        return new Color(r, g, bv);
    }
}
