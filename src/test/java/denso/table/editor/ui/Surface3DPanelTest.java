/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor.ui;

import static org.junit.Assert.*;

import java.awt.Component;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.junit.Test;

import denso.table.editor.model.DensoTable1D;
import denso.table.editor.model.DensoTable2D;

public class Surface3DPanelTest {

    @Test
    public void rendersTablesWithJzy3d() throws Exception {
        DensoTable2D map = new DensoTable2D();
        map.setCountX(3);
        map.setCountY(2);
        map.setValuesX(new float[] { 500f, 1000f, 1500f });
        map.setValuesY(new float[] { 10f, 20f });
        map.setValuesZ(new double[][] { { 1, 2, 3 }, { 4, 5, 6 } });
        map.setHasMAC(true);
        map.setMultiplier(0.5f);
        map.setOffset(-40f);

        DensoTable1D curve = new DensoTable1D();
        curve.setCountX(3);
        curve.setValuesX(new float[] { 0f, 1f, 2f });
        curve.setValuesY(new double[] { 7, 7, 7 });

        // jzy3d renders from queued AWT events, so failures surface on the EDT, not here.
        List<Throwable> edtErrors = Collections.synchronizedList(new ArrayList<>());
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> edtErrors.add(e));
        Surface3DPanel[] panel = new Surface3DPanel[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel[0] = new Surface3DPanel();
                for (Component c : panel[0].getComponents()) {
                    if (c instanceof JLabel label) {
                        assertFalse(label.getText(),
                                label.getText().startsWith("3D rendering unavailable"));
                    }
                }
                panel[0].refresh(map);
                panel[0].refresh(curve);
            });
            SwingUtilities.invokeAndWait(() -> panel[0].refresh(null));
            SwingUtilities.invokeAndWait(() -> panel[0].dispose());
            SwingUtilities.invokeAndWait(() -> {});
        }
        finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
        assertEquals("uncaught EDT exceptions: " + edtErrors, 0, edtErrors.size());
    }
}
