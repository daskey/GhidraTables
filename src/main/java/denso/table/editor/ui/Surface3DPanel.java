/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

import org.jzy3d.chart.Chart;
import org.jzy3d.chart.factories.EmulGLChartFactory;
import org.jzy3d.colors.Color;
import org.jzy3d.maths.Coord3d;
import org.jzy3d.plot3d.primitives.Point;
import org.jzy3d.plot3d.primitives.Polygon;
import org.jzy3d.plot3d.primitives.Shape;
import org.jzy3d.plot3d.rendering.canvas.Quality;

import denso.table.editor.model.*;

/**
 * A 3D surface visualization panel for Denso calibration tables, powered by
 * jzy3d with the EmulGL (pure-Java) rendering backend.
 *
 * <p>For 2D tables, renders a surface mesh colored by Z value.
 * For 1D tables, renders a vertical ribbon along the curve.
 * The view is read-only; mouse drag rotates, scroll wheel zooms.
 */
public class Surface3DPanel extends JPanel {

    private DensoTable table;
    private Chart chart;
    private Shape currentSurface;
    private Component canvasComponent;
    private boolean wireframe = true;

    // Toolbar components
    private JToggleButton wireframeToggle;
    private JButton resetViewBtn;

    public Surface3DPanel() {
        setLayout(new BorderLayout());
        setBackground(GhidraTheme.panelBackground());

        initChart();
        add(buildToolbar(), BorderLayout.SOUTH);
    }

    private void initChart() {
        try {
            Quality q = Quality.Advanced();
            q.setAnimated(false);
            q.setHiDPIEnabled(true);

            chart = new EmulGLChartFactory().newChart(q);
            chart.getView().setBackgroundColor(toJzy3dColor(GhidraTheme.panelBackground()));
            chart.addMouseCameraController();

            canvasComponent = (Component) chart.getCanvas();
            add(canvasComponent, BorderLayout.CENTER);
        } catch (Exception e) {
            // Fallback if jzy3d initialization fails
            JLabel errorLabel = new JLabel("3D rendering unavailable: " + e.getMessage());
            errorLabel.setHorizontalAlignment(SwingConstants.CENTER);
            errorLabel.setForeground(GhidraTheme.secondaryForeground());
            errorLabel.setFont(GhidraTheme.labelFont());
            add(errorLabel, BorderLayout.CENTER);
        }
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        bar.setBackground(GhidraTheme.surfaceBackground());
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, GhidraTheme.subtleBorderColor()));

        wireframeToggle = new JToggleButton("Wireframe", wireframe);
        wireframeToggle.setFont(GhidraTheme.smallFont());
        wireframeToggle.setFocusPainted(false);
        wireframeToggle.setMargin(new Insets(3, 8, 3, 8));
        wireframeToggle.addActionListener(e -> {
            wireframe = wireframeToggle.isSelected();
            if (currentSurface != null) {
                currentSurface.setWireframeDisplayed(wireframe);
                repaintChart();
            }
        });

        resetViewBtn = new JButton("Reset View");
        resetViewBtn.setFont(GhidraTheme.smallFont());
        resetViewBtn.setFocusPainted(false);
        resetViewBtn.setMargin(new Insets(3, 8, 3, 8));
        resetViewBtn.addActionListener(e -> {
            if (chart != null) {
                chart.getView().setViewPoint(new Coord3d(-0.6, 0.5, 0), true);
                repaintChart();
            }
        });

        JLabel hint = new JLabel("Drag to rotate \u00b7 Scroll to zoom");
        hint.setFont(GhidraTheme.smallFont());
        hint.setForeground(GhidraTheme.secondaryForeground());

        bar.add(wireframeToggle);
        bar.add(resetViewBtn);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(hint);

        return bar;
    }

    /**
     * Updates the panel with new table data and repaints.
     */
    public void refresh(DensoTable table) {
        this.table = table;
        if (chart == null) return;

        // Remove previous surface
        if (currentSurface != null) {
            chart.getScene().getGraph().remove(currentSurface, false);
            currentSurface = null;
        }

        if (table == null) return;

        if (table.is2D()) {
            build2DSurface();
        } else {
            build1DSurface();
        }

        if (currentSurface != null) {
            chart.getScene().getGraph().add(currentSurface);
        }

        repaintChart();
    }

    private void build2DSurface() {
        DensoTable2D t2d = (DensoTable2D) table;
        float[] xVals = t2d.getValuesX();
        float[] yVals = t2d.getValuesY();
        double[][] zRaw = t2d.getValuesZ();
        int nx = t2d.getCountX();
        int ny = t2d.getCountY();

        if (nx < 2 || ny < 2) return;

        // Compute physical Z values and range
        double[][] zPhys = new double[ny][nx];
        double zMin = Double.MAX_VALUE, zMax = -Double.MAX_VALUE;
        for (int r = 0; r < ny; r++) {
            for (int c = 0; c < nx; c++) {
                double v = t2d.toPhysical(zRaw[r][c]);
                zPhys[r][c] = v;
                if (v < zMin) zMin = v;
                if (v > zMax) zMax = v;
            }
        }
        if (zMax == zMin) zMax = zMin + 1;

        // Build polygon quads
        List<Polygon> polygons = new ArrayList<>();
        for (int r = 0; r < ny - 1; r++) {
            for (int c = 0; c < nx - 1; c++) {
                Polygon poly = new Polygon();
                poly.add(new Point(new Coord3d(xVals[c], yVals[r], (float) zPhys[r][c])));
                poly.add(new Point(new Coord3d(xVals[c + 1], yVals[r], (float) zPhys[r][c + 1])));
                poly.add(new Point(new Coord3d(xVals[c + 1], yVals[r + 1], (float) zPhys[r + 1][c + 1])));
                poly.add(new Point(new Coord3d(xVals[c], yVals[r + 1], (float) zPhys[r + 1][c])));

                // Color based on average Z value using the heat-map gradient
                double avgZ = (zPhys[r][c] + zPhys[r][c + 1] + zPhys[r + 1][c + 1] + zPhys[r + 1][c]) / 4.0;
                double norm = (avgZ - zMin) / (zMax - zMin);
                poly.setColor(heatToJzy3d(norm));

                polygons.add(poly);
            }
        }

        currentSurface = new Shape(polygons);
        currentSurface.setFaceDisplayed(true);
        currentSurface.setWireframeDisplayed(wireframe);
        currentSurface.setWireframeColor(Color.BLACK);

        // Configure axis labels
        chart.getAxisLayout().setXAxisLabel("X");
        chart.getAxisLayout().setYAxisLabel("Y");
        chart.getAxisLayout().setZAxisLabel("Z");
    }

    private void build1DSurface() {
        DensoTable1D t1d = (DensoTable1D) table;
        float[] xVals = t1d.getValuesX();
        double[] yRaw = t1d.getValuesY();
        int n = t1d.getCountX();

        if (n < 2) return;

        // Compute physical Y values and range
        double[] yPhys = new double[n];
        double yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double v = t1d.toPhysical(yRaw[i]);
            yPhys[i] = v;
            if (v < yMin) yMin = v;
            if (v > yMax) yMax = v;
        }
        if (yMax == yMin) yMax = yMin + 1;

        // Build vertical ribbon quads from floor to curve
        float floor = (float) yMin;
        List<Polygon> polygons = new ArrayList<>();
        for (int i = 0; i < n - 1; i++) {
            Polygon poly = new Polygon();
            poly.add(new Point(new Coord3d(xVals[i], 0f, floor)));
            poly.add(new Point(new Coord3d(xVals[i + 1], 0f, floor)));
            poly.add(new Point(new Coord3d(xVals[i + 1], 0f, (float) yPhys[i + 1])));
            poly.add(new Point(new Coord3d(xVals[i], 0f, (float) yPhys[i])));

            double avgY = (yPhys[i] + yPhys[i + 1]) / 2.0;
            double norm = (avgY - yMin) / (yMax - yMin);
            poly.setColor(heatToJzy3d(norm));

            polygons.add(poly);
        }

        currentSurface = new Shape(polygons);
        currentSurface.setFaceDisplayed(true);
        currentSurface.setWireframeDisplayed(wireframe);
        currentSurface.setWireframeColor(Color.BLACK);

        chart.getAxisLayout().setXAxisLabel("X");
        chart.getAxisLayout().setYAxisLabel("");
        chart.getAxisLayout().setZAxisLabel("Y");
    }

    private void repaintChart() {
        if (canvasComponent != null) {
            canvasComponent.repaint();
        }
    }

    /**
     * Converts a normalized [0,1] heat-map value to a jzy3d Color using the
     * same gradient as {@link HeatMapCellRenderer#heatColor(double)}.
     */
    private static Color heatToJzy3d(double norm) {
        java.awt.Color awt = HeatMapCellRenderer.heatColor(norm);
        return new Color(awt.getRed() / 255f, awt.getGreen() / 255f, awt.getBlue() / 255f, 1f);
    }

    private static Color toJzy3dColor(java.awt.Color awt) {
        return new Color(awt.getRed() / 255f, awt.getGreen() / 255f, awt.getBlue() / 255f, 1f);
    }

    /**
     * Cleans up chart resources when the panel is no longer needed.
     */
    public void dispose() {
        if (chart != null) {
            try {
                chart.dispose();
            } catch (Exception ignored) {}
            chart = null;
        }
    }
}
