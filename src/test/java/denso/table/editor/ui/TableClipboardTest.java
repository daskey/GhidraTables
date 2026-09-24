package denso.table.editor.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public class TableClipboardTest {
    @Test public void acceptsSpreadsheetLineEndingsAndFinalNewline() {
        double[][] values = TableClipboard.parse(" 1.25 \t2\r\n3\t4\r\n");
        assertArrayEquals(new double[] {1.25, 2}, values[0], 0);
        assertArrayEquals(new double[] {3, 4}, values[1], 0);
    }

    @Test public void emptyEdgeCellsAreRejectedRatherThanShiftingData() {
        for (String text : new String[] {"\t2", "1\t", "1\t\n", "1\t2\n3\t", ""}) {
            assertThrows(IllegalArgumentException.class, () -> TableClipboard.parse(text));
        }
    }

    @Test public void rejectsUnequalRowWidths() {
        assertThrows(IllegalArgumentException.class, () -> TableClipboard.parse("1\t2\n3"));
    }

    @Test public void rejectsNonFiniteOrNonnumericCells() {
        for (String text : new String[] {"1\tNaN", "1\tInfinity", "1\tvalue"}) {
            assertThrows(IllegalArgumentException.class, () -> TableClipboard.parse(text));
        }
    }
}
