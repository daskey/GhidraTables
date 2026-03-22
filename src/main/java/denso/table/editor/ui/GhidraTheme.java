/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor.ui;

import java.awt.*;
import javax.swing.*;

import generic.theme.GThemeDefaults;
import generic.theme.Gui;

/**
 * Resolves colors and fonts from the active Ghidra/Swing theme so custom UI
 * elements track the user's current look-and-feel instead of a fixed palette.
 */
final class GhidraTheme {

    private static final Font FALLBACK_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);

    private GhidraTheme() {}

    static Font labelFont() {
        return font("Label.font", FALLBACK_FONT);
    }

    static Font smallFont() {
        Font base = labelFont();
        return base.deriveFont(base.getStyle(), Math.max(11f, base.getSize2D() - 1f));
    }

    static Font boldFont() {
        return labelFont().deriveFont(Font.BOLD);
    }

    static Font titleFont() {
        Font base = labelFont();
        return base.deriveFont(Font.BOLD, base.getSize2D() + 2f);
    }

    static Font tableFont() {
        return font("Table.font", labelFont());
    }

    static Font tableHeaderFont() {
        return font("TableHeader.font", boldFont());
    }

    static Font textFieldFont() {
        return font("TextField.font", labelFont());
    }

    static Color panelBackground() {
        return color("Panel.background", GThemeDefaults.Colors.BACKGROUND);
    }

    static Color surfaceBackground() {
        return shift(panelBackground(), Gui.isDarkTheme() ? 0.04f : -0.03f);
    }

    static Color cardBackground() {
        return shift(panelBackground(), Gui.isDarkTheme() ? 0.07f : -0.05f);
    }

    static Color primaryForeground() {
        return color("Label.foreground", GThemeDefaults.Colors.FOREGROUND);
    }

    static Color secondaryForeground() {
        return GThemeDefaults.Colors.Messages.HINT;
    }

    static Color linkForeground() {
        return GThemeDefaults.Colors.Messages.NORMAL;
    }

    static Color errorForeground() {
        return GThemeDefaults.Colors.Messages.ERROR;
    }

    static Color borderColor() {
        return color("Separator.foreground",
            color("controlShadow", GThemeDefaults.Colors.BORDER));
    }

    static Color tableBackground() {
        return color("Table.background", panelBackground());
    }

    static Color tableForeground() {
        return color("Table.foreground", primaryForeground());
    }

    static Color tableGridColor() {
        if (Gui.hasColor("color.bg.table.grid")) {
            return Gui.getColor("color.bg.table.grid");
        }
        return color("Table.gridColor", borderColor());
    }

    static Color tableSelectionBackground() {
        if (Gui.hasColor("color.bg.selection")) {
            return color("Table.selectionBackground", Gui.getColor("color.bg.selection"));
        }
        return color("Table.selectionBackground",
            shift(tableBackground(), Gui.isDarkTheme() ? 0.18f : -0.18f));
    }

    static Color tableSelectionForeground() {
        return color("Table.selectionForeground",
            color("textHighlightText", tableForeground()));
    }

    static Color tableHeaderBackground() {
        return color("TableHeader.background", surfaceBackground());
    }

    static Color tableHeaderForeground() {
        return color("TableHeader.foreground", primaryForeground());
    }

    static Color textFieldBackground() {
        return color("TextField.background", shift(panelBackground(), 0.10f));
    }

    static Color textFieldForeground() {
        return color("TextField.foreground", primaryForeground());
    }

    static Color textFieldCaret() {
        return color("TextField.caretForeground", textFieldForeground());
    }

    static Color invalidFieldBackground() {
        if (Gui.hasColor("color.bg.textfield.hint.invalid")) {
            return Gui.getColor("color.bg.textfield.hint.invalid");
        }
        return mix(textFieldBackground(), errorForeground(), 0.18f);
    }

    static Color mix(Color base, Color overlay, float amount) {
        float clamped = Math.max(0f, Math.min(1f, amount));
        float keep = 1f - clamped;
        int r = Math.round(base.getRed() * keep + overlay.getRed() * clamped);
        int g = Math.round(base.getGreen() * keep + overlay.getGreen() * clamped);
        int b = Math.round(base.getBlue() * keep + overlay.getBlue() * clamped);
        return new Color(r, g, b);
    }

    private static Color shift(Color base, float amount) {
        return amount >= 0f
            ? mix(base, Color.WHITE, amount)
            : mix(base, Color.BLACK, -amount);
    }

    private static Color color(String uiKey, Color fallback) {
        Color color = UIManager.getColor(uiKey);
        return color != null ? color : fallback;
    }

    private static Font font(String uiKey, Font fallback) {
        Font font = UIManager.getFont(uiKey);
        return font != null ? font : fallback;
    }
}
