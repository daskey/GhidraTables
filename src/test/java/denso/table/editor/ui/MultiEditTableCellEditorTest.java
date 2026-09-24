package denso.table.editor.ui;

import java.awt.event.KeyEvent;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.junit.Test;
import static org.junit.Assert.*;

public class MultiEditTableCellEditorTest {
    private static final class Fixture {
        final double[] values = {1, 2, 3};
        int commits;
        int automaticWrites;
        final DefaultTableModel model = new DefaultTableModel(new Object[][] {{"1", "2", "3"}},
                new Object[] {"A", "B", "C"}) {
            @Override public void setValueAt(Object value, int row, int column) { automaticWrites++; }
        };
        final JTable table = new JTable(model);
        final MultiEditTableCellEditor editor = new MultiEditTableCellEditor((value, rows, cols) -> {
            if (value > 255) throw new IllegalArgumentException("out of range");
            commits++;
            for (int col : cols) values[col] = value;
            return cols.length;
        });
        Fixture() {
            table.setCellSelectionEnabled(true);
            table.setRowSelectionInterval(0, 0);
            table.setColumnSelectionInterval(0, 2);
            table.setDefaultEditor(Object.class, editor);
        }
        JTextField start() {
            assertTrue(table.editCellAt(0, 0));
            return (JTextField) table.getEditorComponent();
        }
    }

    @Test public void unchangedCommitDoesNotSpreadLeadCellAcrossSelection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture f = new Fixture(); f.start();
            assertTrue(f.editor.stopCellEditing());
            assertArrayEquals(new double[] {1, 2, 3}, f.values, 0);
            assertEquals(0, f.commits);
            assertEquals(0, f.automaticWrites);
            assertFalse(f.table.isEditing());
        });
    }

    @Test public void deliberatelyRetypingSameValueFillsSelectionOnce() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture f = new Fixture(); f.start().setText("1");
            assertTrue(f.editor.stopCellEditing());
            assertArrayEquals(new double[] {1, 1, 1}, f.values, 0);
            assertEquals(1, f.commits);
            assertEquals(0, f.automaticWrites);
        });
    }

    @Test public void typingStartsAReplacementRatherThanAppending() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture f = new Fixture();
            KeyEvent event = new KeyEvent(f.table, KeyEvent.KEY_TYPED,
                    System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, '9');
            assertTrue(f.table.editCellAt(0, 0, event));
            JTextField field = (JTextField) f.table.getEditorComponent();
            assertEquals("", field.getText());
            field.replaceSelection("9");
            assertTrue(f.editor.stopCellEditing());
            assertArrayEquals(new double[] {9, 9, 9}, f.values, 0);
        });
    }

    @Test public void invalidEditStaysOpenAndChangesNoValues() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture f = new Fixture(); JTextField field = f.start();
            for (String value : new String[] {"NaN", "Infinity", "256", "oops"}) {
                field.setText(value);
                assertFalse(f.editor.stopCellEditing());
                assertTrue(f.table.isEditing());
                assertArrayEquals(new double[] {1, 2, 3}, f.values, 0);
            }
            f.editor.cancelCellEditing();
            assertFalse(f.table.isEditing());
            assertEquals(0, f.commits);
        });
    }

    @Test public void selectionIsCapturedAtEditStart() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture f = new Fixture(); JTextField field = f.start();
            f.table.setColumnSelectionInterval(2, 2);
            field.setText("8");
            assertTrue(f.editor.stopCellEditing());
            assertArrayEquals(new double[] {8, 8, 8}, f.values, 0);
        });
    }
}
