package denso.table.editor;

import java.awt.GraphicsEnvironment;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import javax.swing.*;
import org.junit.*;
import static org.junit.Assert.*;
import denso.table.editor.model.*;
import denso.table.editor.ui.GhidraTablesEditorFrame;

/** Window-level regressions; run with -Djava.awt.headless=false under a display or Xvfb. */
public class GhidraTablesEditorFrameTest {
    private TestMemory memory;
    private TestMemory.Region bytes;
    private DensoTable1D table;
    private GhidraTablesEditorFrame frame;
    private JTable grid;

    @Before public void setUp() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            memory = new TestMemory();
            bytes = memory.add(0x1000, 0x1000);
            bytes.at(0x1800).putFloat(1000).putFloat(2000).putFloat(3000);
            bytes.at(0x1900).put((byte) 10).put((byte) 20).put((byte) 30);
            table = new DensoTable1D();
            table.setCountX(3); table.setPtrX(0x1800); table.setPtrY(0x1900);
            table.setHeaderAddress(0x1100); table.setDataType(DensoTableType.UINT8);
            frame = new GhidraTablesEditorFrame(table, memory.program, null, null);
            grid = field("grid", JTable.class);
            grid.setRowSelectionInterval(1, 1);
            grid.setColumnSelectionInterval(0, 2);
        });
    }

    @After public void tearDown() throws Exception {
        if (frame != null) SwingUtilities.invokeAndWait(frame::dispose);
    }

    @Test public void multiEditIsQuantizedAndUndoRestoresCleanState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(grid.editCellAt(1, 0));
            ((JTextField) grid.getEditorComponent()).setText("15.6");
            assertTrue(grid.getCellEditor().stopCellEditing());
            assertArrayEquals(new double[] {16, 16, 16}, table.getValuesY(), 0);
            assertTrue(frame.hasUnsavedChanges());
            invoke("undoLastOperation");
            assertArrayEquals(new double[] {10, 20, 30}, table.getValuesY(), 0);
            assertFalse(frame.hasUnsavedChanges());
            invoke("redoLastOperation");
            assertArrayEquals(new double[] {16, 16, 16}, table.getValuesY(), 0);
        });
    }

    @Test public void saveCommitsPendingTypingAndNotifiesList() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            int[] notifications = {0};
            frame.setSavedListener(() -> notifications[0]++);
            grid.editCellAt(1, 0);
            ((JTextField) grid.getEditorComponent()).setText("25.7");
            assertTrue(frame.hasUnsavedChanges());
            assertTrue(field("saveBtn", JButton.class).isEnabled());
            invoke("applyChanges");
            assertEquals(26, bytes.at(0x1900).get() & 0xff);
            assertArrayEquals(new double[] {26, 26, 26}, table.getValuesY(), 0);
            assertFalse(frame.hasUnsavedChanges());
            assertEquals(1, notifications[0]);
        });
    }

    @Test public void singleAnchorPastesWholeRectangleAndUndoesAsOneEdit() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            grid.setColumnSelectionInterval(0, 0);
            invoke("pasteText", "31\t32\t33\n");
            assertArrayEquals(new double[] {31, 32, 33}, table.getValuesY(), 0);
            invoke("undoLastOperation");
            assertFalse(frame.hasUnsavedChanges());
        });
    }

    @Test public void invalidPasteRollsBackEarlierValidCells() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            grid.setColumnSelectionInterval(0, 0);
            assertThrows(IllegalArgumentException.class, () -> invoke("pasteText", "40\t256\t42"));
            assertArrayEquals(new double[] {10, 20, 30}, table.getValuesY(), 0);
            assertFalse(frame.hasUnsavedChanges());
        });
    }

    @Test public void readOnlyAxisSelectionIsExcludedFromStatistics() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            grid.setRowSelectionInterval(0, 0);
            invoke("updateStatus");
            assertTrue(field("selectionSummaryLabel", JTextArea.class).getText().contains("read-only"));
            grid.setRowSelectionInterval(0, 1);
            invoke("updateStatus");
            assertTrue(field("selectionSummaryLabel", JTextArea.class).getText().startsWith("3 editable cells"));
        });
    }

    @Test public void invalidTypedValueCannotBeSavedOrApplied() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            grid.editCellAt(1, 0);
            ((JTextField) grid.getEditorComponent()).setText("256");
            invoke("applyChanges");
            assertTrue(grid.isEditing());
            assertArrayEquals(new double[] {10, 20, 30}, table.getValuesY(), 0);
            assertEquals(10, bytes.at(0x1900).get() & 0xff);
        });
    }

    @Test public void curveToolsDoNotOverwriteUnselectedGaps() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            invoke("pasteText", "0\t99\t10");
            grid.setColumnSelectionInterval(0, 0);
            grid.addColumnSelectionInterval(2, 2);
            invoke("interpolateSelected");
            assertArrayEquals(new double[] {0, 99, 10}, table.getValuesY(), 0);
            invoke("undoLastOperation");
            assertArrayEquals(new double[] {10, 20, 30}, table.getValuesY(), 0);
        });
    }

    @Test public void wheelBurstIsOneUndoAndSaturates() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            grid.setSize(500, 150);
            java.awt.Rectangle cell = grid.getCellRect(1, 0, true);
            for (int i = 0; i < 2; i++) {
                grid.dispatchEvent(new MouseWheelEvent(grid, MouseEvent.MOUSE_WHEEL,
                        System.currentTimeMillis(), java.awt.event.InputEvent.SHIFT_DOWN_MASK,
                        cell.x + 2, cell.y + 2, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -20));
            }
            assertArrayEquals(new double[] {255, 255, 255}, table.getValuesY(), 0);
            invoke("undoLastOperation");
            assertArrayEquals(new double[] {10, 20, 30}, table.getValuesY(), 0);
            assertFalse(frame.hasUnsavedChanges());
        });
    }

    @Test public void ordinaryWheelEventsReachScrollPane() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            grid.clearSelection();
            JScrollPane pane = field("tableScrollPane", JScrollPane.class);
            int[] forwarded = {0};
            pane.addMouseWheelListener(e -> forwarded[0]++);
            grid.dispatchEvent(new MouseWheelEvent(grid, MouseEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(), 0, 2, 2, 0, false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1));
            assertEquals(1, forwarded[0]);
        });
    }

    private <T> T field(String name, Class<T> type) {
        try {
            Field f = frame.getClass().getDeclaredField(name); f.setAccessible(true);
            return type.cast(f.get(frame));
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }
    private Object invoke(String name, Object... args) {
        try {
            Class<?>[] types = java.util.Arrays.stream(args).map(Object::getClass).toArray(Class<?>[]::new);
            Method method = frame.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
            return method.invoke(frame, args);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) throw runtime;
            if (ex.getCause() instanceof Error error) throw error;
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }
}
