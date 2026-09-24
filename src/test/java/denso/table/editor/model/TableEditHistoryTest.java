package denso.table.editor.model;

import org.junit.Test;
import static org.junit.Assert.*;

public class TableEditHistoryTest {
    private final DensoTable1D table = new DensoTable1D();
    { table.setValuesY(new double[] {1, 2, 3}); }
    private final TableEditHistory history = new TableEditHistory(table);

    @Test public void batchIsOneUndoAndRedo() {
        history.apply("fill", () -> java.util.Arrays.fill(table.getValuesY(), 7));
        assertTrue(history.isDataDirty());
        assertEquals("fill", history.undo());
        assertArrayEquals(new double[] {1, 2, 3}, table.getValuesY(), 0);
        assertFalse(history.isDataDirty());
        assertNull(history.undo());
        assertEquals("fill", history.redo());
        assertArrayEquals(new double[] {7, 7, 7}, table.getValuesY(), 0);
    }

    @Test public void invalidBatchRollsBackAllEarlierCellsAndMac() {
        assertThrows(IllegalArgumentException.class, () -> history.apply("paste", () -> {
            table.getValuesY()[0] = 9;
            table.setMultiplier(5);
            throw new IllegalArgumentException("bad second cell");
        }));
        assertFalse(history.isDataDirty());
        assertFalse(history.isMacDirty());
        assertEquals(1, table.getMultiplier(), 0);
        assertNull(history.undo());
    }

    @Test public void noOpDoesNotDisplaceUsefulUndo() {
        history.apply("edit", () -> table.getValuesY()[0] = 5);
        assertFalse(history.apply("no-op", () -> table.getValuesY()[0] = 5));
        assertEquals("edit", history.undo());
        assertFalse(history.isDataDirty());
    }

    @Test public void savedStateRemainsAccurateAcrossUndoAndRedo() {
        history.apply("edit", () -> table.getValuesY()[0] = 5);
        history.markSaved();
        assertFalse(history.isDataDirty());
        history.undo();
        assertTrue(history.isDataDirty());
        history.redo();
        assertFalse(history.isDataDirty());
    }

    @Test public void macUndoDoesNotChangeRawPayload() {
        history.apply("MAC", () -> { table.setMultiplier(-2); table.setOffset(40); });
        assertTrue(history.isMacDirty());
        assertFalse(history.isDataDirty());
        history.undo();
        assertFalse(history.isMacDirty());
        assertArrayEquals(new double[] {1, 2, 3}, table.getValuesY(), 0);
    }

    @Test public void wheelBurstMergesButSelectionChangeSeparates() {
        history.apply("wheel", "row1", () -> table.getValuesY()[0] = 2);
        history.apply("wheel", "row1", () -> table.getValuesY()[0] = 3);
        history.apply("wheel", "row2", () -> table.getValuesY()[1] = 4);
        history.undo();
        assertArrayEquals(new double[] {3, 2, 3}, table.getValuesY(), 0);
        history.undo();
        assertFalse(history.isDataDirty());
        assertNull(history.undo());
    }

    @Test public void freshEditDiscardsRedoAndResetDiscardsAllHistory() {
        history.apply("first", () -> table.getValuesY()[0] = 5);
        history.undo();
        history.apply("second", () -> table.getValuesY()[0] = 6);
        assertNull(history.redo());
        history.reset();
        assertNull(history.undo());
        assertFalse(history.isDataDirty());
    }

    @Test public void historyIsBounded() {
        for (int i = 0; i < 60; i++) {
            final double value = 10 + i;
            history.apply("edit", () -> table.getValuesY()[0] = value);
        }
        int count = 0;
        while (history.undo() != null) count++;
        assertEquals(50, count);
    }
}
