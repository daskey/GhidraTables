/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor.model;

import static org.junit.Assert.*;

import org.junit.Test;

public class DensoTableTypeTest {

    private static final DensoTableType[] STORAGE_TYPES = {
        DensoTableType.FLOAT, DensoTableType.UINT8, DensoTableType.UINT16,
        DensoTableType.INT8, DensoTableType.INT16, DensoTableType.UINT32
    };

    @Test
    public void fromCodeResolvesKnownCodesAndRejectsOthers() {
        for (DensoTableType type : STORAGE_TYPES) {
            assertSame(type, DensoTableType.fromCode(type.getCode()));
        }
        assertSame(DensoTableType.UNDEFINED, DensoTableType.fromCode(0x01));
        assertSame(DensoTableType.UNDEFINED, DensoTableType.fromCode(-1 & 0xFF));
        assertFalse(DensoTableType.UNDEFINED.isValid());
    }

    @Test
    public void rawToDoubleHonorsSignedness() {
        assertEquals(255.0, DensoTableType.UINT8.rawToDouble(0xFF), 0);
        assertEquals(-1.0, DensoTableType.INT8.rawToDouble(0xFF), 0);
        assertEquals(65535.0, DensoTableType.UINT16.rawToDouble(0xFFFF), 0);
        assertEquals(-32768.0, DensoTableType.INT16.rawToDouble(0x8000), 0);
        assertEquals(4294967295.0, DensoTableType.UINT32.rawToDouble(0xFFFFFFFFL), 0);
        assertEquals(1.5, DensoTableType.FLOAT.rawToDouble(Float.floatToIntBits(1.5f)), 0);
    }

    @Test
    public void doubleToRawRoundTripsRangeEndpoints() {
        for (DensoTableType type : STORAGE_TYPES) {
            if (!type.isIntegral()) continue;
            for (double v : new double[] { type.getMinRaw(), type.getMaxRaw(), 0 }) {
                long bits = type.doubleToRaw(v);
                assertEquals(type + " " + v, v, type.rawToDouble(bits), 0);
            }
        }
        assertEquals(0xFFL, DensoTableType.INT8.doubleToRaw(-1));
        assertEquals(0x8000L, DensoTableType.INT16.doubleToRaw(-32768));
    }

    @Test
    public void doubleToRawRejectsOutOfRangeAndNonFinite() {
        assertThrows(IllegalArgumentException.class, () -> DensoTableType.UINT8.doubleToRaw(256));
        assertThrows(IllegalArgumentException.class, () -> DensoTableType.UINT8.doubleToRaw(-1));
        assertThrows(IllegalArgumentException.class, () -> DensoTableType.INT8.doubleToRaw(128));
        assertThrows(IllegalArgumentException.class, () -> DensoTableType.INT16.doubleToRaw(-32769));
        assertThrows(IllegalArgumentException.class, () -> DensoTableType.FLOAT.doubleToRaw(1e39));
        for (DensoTableType type : STORAGE_TYPES) {
            assertThrows(IllegalArgumentException.class, () -> type.doubleToRaw(Double.NaN));
            assertThrows(IllegalArgumentException.class,
                    () -> type.doubleToRaw(Double.POSITIVE_INFINITY));
        }
    }

    @Test
    public void isRawInRangeMatchesDoubleToRaw() {
        double[] samples = { -1e40, -70000, -32768.5, -32768, -129, -128.4, -0.5, -0.4, 0,
                0.49, 0.5, 127.4, 127.5, 255, 255.4, 255.5, 65535, 65535.5, 4294967295.0,
                4294967295.5, 1e39, Double.NaN, Double.NEGATIVE_INFINITY };
        for (DensoTableType type : STORAGE_TYPES) {
            for (double v : samples) {
                boolean accepted;
                try {
                    type.doubleToRaw(v);
                    accepted = true;
                }
                catch (IllegalArgumentException e) {
                    accepted = false;
                }
                assertEquals(type + " " + v, accepted, type.isRawInRange(v));
            }
        }
    }

    @Test
    public void quantizeRawRoundsLikeDoubleToRaw() {
        assertEquals(12.0, DensoTableType.UINT8.quantizeRaw(12.3), 0);
        assertEquals(13.0, DensoTableType.UINT8.quantizeRaw(12.5), 0);
        assertEquals(-2.0, DensoTableType.INT8.quantizeRaw(-2.4), 0);
        assertEquals((double) (float) 0.1, DensoTableType.FLOAT.quantizeRaw(0.1), 0);
    }

    @Test
    public void quantizeRawSaturatesAtTypeRange() {
        assertEquals(255.0, DensoTableType.UINT8.quantizeRaw(300), 0);
        assertEquals(0.0, DensoTableType.UINT16.quantizeRaw(-5), 0);
        assertEquals(-128.0, DensoTableType.INT8.quantizeRaw(-1000), 0);
        assertEquals(32767.0, DensoTableType.INT16.quantizeRaw(1e9), 0);
        assertEquals(4294967295.0, DensoTableType.UINT32.quantizeRaw(1e12), 0);
        assertEquals(Float.MAX_VALUE, DensoTableType.FLOAT.quantizeRaw(1e39), 0);
        assertTrue(Double.isNaN(DensoTableType.UINT8.quantizeRaw(Double.NaN)));
    }

    @Test
    public void quantizedValuesAreAlwaysWritable() {
        double[] samples = { -1e40, -1e6, -300.7, -1.5, -0.2, 0, 0.7, 99.5, 254.6, 70000.1, 1e12 };
        for (DensoTableType type : STORAGE_TYPES) {
            for (double v : samples) {
                double q = type.quantizeRaw(v);
                assertTrue(type + " " + v + " -> " + q, type.isRawInRange(q));
                assertEquals("quantize is idempotent", q, type.quantizeRaw(q), 0);
                assertEquals("stored value round-trips", q, type.rawToDouble(type.doubleToRaw(q)), 0);
            }
        }
    }
}
