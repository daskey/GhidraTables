/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor.ui;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.Border;
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

        CellRole role = getRoleFor(row, col);

        switch (role) {
            case CORNER -> {
                label.setBackground(GhidraTheme.surfaceBackground());
                label.setForeground(GhidraTheme.secondaryForeground());
                label.setFont(label.getFont().deriveFont(Font.BOLD | Font.ITALIC));
                label.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 1, GhidraTheme.subtleBorderColor()),
                        BorderFactory.createEmptyBorder(2, 4, 2, 4)));
            }
            case X_HEADER, Y_HEADER -> {
                Color headerBg = (role == CellRole.Y_HEADER && row % 2 == 0)
                        ? GhidraTheme.tableHeaderStripeBackground()
                        : GhidraTheme.tableHeaderBackground();
                label.setBackground(headerBg);
                label.setForeground(GhidraTheme.tableHeaderForeground());
                label.setFont(label.getFont().deriveFont(Font.BOLD));
                label.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0,
                                role == CellRole.X_HEADER ? 1 : 0,
                                role == CellRole.Y_HEADER ? 1 : 0,
                                GhidraTheme.subtleBorderColor()),
                        BorderFactory.createEmptyBorder(2, 6, 2, 6)));
            }
            case DATA -> {
                double numeric = parseDouble(value);
                double norm = normalize(numeric);
                Color baseBg = row % 2 == 0
                        ? GhidraTheme.tableBackground()
                        : GhidraTheme.tableStripeBackground();
                Color bg = blend(baseBg, heatColor(norm), 0.88f);
                Border innerBorder = BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 1, GhidraTheme.tableGridColor()),
                        BorderFactory.createEmptyBorder(2, 6, 2, 6));

                if (isSelected) {
                    Color selected = GhidraTheme.tableSelectionBackground();
                    bg = blend(bg, selected, 0.42f);
                }

                label.setBackground(bg);
                label.setForeground(isSelected
                        ? GhidraTheme.tableSelectionForeground()
                        : contrastText(bg));
                label.setFont(label.getFont().deriveFont(Font.PLAIN));

                if (isSelected && hasFocus) {
                    label.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(GhidraTheme.focusRingColor(), 2),
                            innerBorder));
                } else if (isSelected) {
                    label.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(
                                    GhidraTheme.withAlpha(GhidraTheme.tableSelectionBackground(), 160), 1),
                            innerBorder));
                } else {
                    label.setBorder(innerBorder);
                }
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
     * Maps a normalised value [0,1] to a perceptually smoother heat-map using
     * a 5-stop gradient:  deep blue → cyan → green → amber → red.
     * The stops are tuned so mid-range values (greens/yellows) occupy more
     * visual space, matching human colour sensitivity.
     */
    public static Color heatColor(double t) {
        t = Math.max(0, Math.min(1, t));

        // 5-stop gradient with adjusted breakpoints for better perceptual uniformity
        int r, g, b;
        if (t < 0.20) {
            // Deep blue → Cyan
            double s = t / 0.20;
            r = (int) (20 * (1 - s));
            g = (int) (60 + 195 * s);
            b = (int) (220 + 35 * s);
        } else if (t < 0.45) {
            // Cyan → Green
            double s = (t - 0.20) / 0.25;
            r = (int) (40 * s);
            g = (int) (220 + 35 * (1 - s * 0.3));
            b = (int) (255 * (1 - s));
        } else if (t < 0.65) {
            // Green → Amber/Yellow
            double s = (t - 0.45) / 0.20;
            r = (int) (40 + 215 * s);
            g = (int) (230 - 20 * s);
            b = (int) (20 * (1 - s));
        } else if (t < 0.85) {
            // Amber → Orange-Red
            double s = (t - 0.65) / 0.20;
            r = 255;
            g = (int) (210 - 140 * s);
            b = 0;
        } else {
            // Orange-Red → Deep Red
            double s = (t - 0.85) / 0.15;
            r = (int) (255 - 35 * s);
            g = (int) (70 - 50 * s);
            b = (int) (15 * s);
        }

        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        Color raw = new Color(r, g, b);

        // Adjust for dark themes: slightly desaturate and brighten to avoid
        // washing out against dark backgrounds
        if (Gui.isDarkTheme()) {
            Color bg = GhidraTheme.tableBackground();
            return blend(raw, bg, 0.12f);
        }
        return raw;
    }

    /**
     * Picks white or black text for maximum contrast against the given
     * background, using WCAG relative luminance.
     */
    private static Color contrastText(Color bg) {
        double lum = relativeLuminance(bg);
        return lum < 0.40 ? new Color(240, 240, 240) : new Color(30, 30, 30);
    }

    /** WCAG relative luminance for contrast calculations. */
    private static double relativeLuminance(Color c) {
        return 0.2126 * linearize(c.getRed())
             + 0.7152 * linearize(c.getGreen())
             + 0.0722 * linearize(c.getBlue());
    }

    private static double linearize(int channel) {
        double s = channel / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** Blends two colours. {@code t} = weight of {@code b}. */
    private static Color blend(Color a, Color b, float t) {
        float s = 1 - t;
        int r = Math.min(255, Math.max(0, (int) (a.getRed()   * s + b.getRed()   * t)));
        int g = Math.min(255, Math.max(0, (int) (a.getGreen() * s + b.getGreen() * t)));
        int bv= Math.min(255, Math.max(0, (int) (a.getBlue()  * s + b.getBlue()  * t)));
        return new Color(r, g, bv);
    }
}
