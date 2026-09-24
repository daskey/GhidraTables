package denso.table.editor.model;

import org.junit.Test;
import static org.junit.Assert.*;

public class DensoTableTypeTest {
    @Test public void everyStorageTypeRoundTripsItsBounds() {
        for (DensoTableType type : DensoTableType.values()) {
            if (!type.isValid()) continue;
            assertEquals(type.minimumValue(), type.quantize(type.minimumValue()), 0);
            assertEquals(type.maximumValue(), type.quantize(type.maximumValue()), 0);
        }
    }

    @Test public void signedBitsAndUnsigned32ArePreserved() {
        assertEquals(255, DensoTableType.INT8.doubleToRaw(-1));
        assertEquals(-128, DensoTableType.INT8.rawToDouble(128), 0);
        assertEquals(65535, DensoTableType.INT16.doubleToRaw(-1));
        assertEquals(4294967295L, DensoTableType.UINT32.doubleToRaw(4294967295d));
    }

    @Test public void editsUseTheSameRoundingAsWrites() {
        assertEquals(13, DensoTableType.UINT8.quantize(12.5), 0);
        assertEquals(-12, DensoTableType.INT8.quantize(-12.5), 0);
        assertEquals((double) (float) 0.1, DensoTableType.FLOAT.quantize(0.1), 0);
    }

    @Test public void nonFiniteAndUndefinedValuesAreRejected() {
        for (DensoTableType type : DensoTableType.values()) {
            for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                assertThrows(IllegalArgumentException.class, () -> type.quantize(value));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> DensoTableType.UNDEFINED.quantize(1));
    }

    @Test public void typedOverflowIsRejectedButWheelSaturates() {
        assertThrows(IllegalArgumentException.class, () -> DensoTableType.INT8.quantize(128));
        assertThrows(IllegalArgumentException.class, () -> DensoTableType.UINT8.quantize(-1));
        assertThrows(IllegalArgumentException.class, () -> DensoTableType.FLOAT.quantize(Double.MAX_VALUE));
        assertEquals(127, DensoTableType.INT8.clampAndQuantize(500), 0);
        assertEquals(-128, DensoTableType.INT8.clampAndQuantize(-500), 0);
    }

    @Test public void negativeMacConvertsAndQuantizesInStorageUnits() {
        DensoTable1D table = new DensoTable1D();
        table.setHasMAC(true);
        table.setMultiplier(-0.5f);
        table.setOffset(100);
        table.setDataType(DensoTableType.UINT8);
        assertEquals(6, table.getDataType().quantize(table.toRaw(97.2)), 0);
        assertEquals(97, table.toPhysical(6), 0);
        table.setMultiplier(0);
        assertThrows(IllegalArgumentException.class, () -> table.toRaw(4));
    }

    @Test public void twoDimensionalCopiesDoNotShareArrays() {
        DensoTable2D table = new DensoTable2D();
        table.setValuesX(new float[] {1});
        table.setValuesY(new float[] {2});
        table.setValuesZ(new double[][] {{3}});
        DensoTable2D copy = (DensoTable2D) table.copy();
        copy.getValuesX()[0] = 4;
        copy.getValuesY()[0] = 5;
        copy.getValuesZ()[0][0] = 6;
        assertEquals(1, table.getValuesX()[0], 0);
        assertEquals(2, table.getValuesY()[0], 0);
        assertEquals(3, table.getZ(0, 0), 0);
    }
}
