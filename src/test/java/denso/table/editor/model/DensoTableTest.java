/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor.model;

import static org.junit.Assert.*;

import org.junit.Test;

public class DensoTableTest {

    @Test
    public void macConversionIsIdentityWithoutMac() {
        DensoTable1D t = new DensoTable1D();
        t.setMultiplier(0.5f);
        t.setOffset(10f);
        assertEquals(42.0, t.toPhysical(42), 0);
        assertEquals(42.0, t.toRaw(42), 0);
    }

    @Test
    public void macConversionRoundTrips() {
        DensoTable2D t = new DensoTable2D();
        t.setHasMAC(true);
        t.setMultiplier(0.25f);
        t.setOffset(-40f);
        assertEquals(-40.0 + 200 * 0.25, t.toPhysical(200), 1e-9);
        assertEquals(200.0, t.toRaw(t.toPhysical(200)), 1e-9);

        t.setMultiplier(-2f);
        assertEquals(17.0, t.toRaw(t.toPhysical(17)), 1e-9);
    }

    @Test
    public void isValidFloatUsesScoobyRomRange() {
        assertTrue(DensoTable.isValidFloat(0f));
        assertTrue(DensoTable.isValidFloat(-0f));
        assertTrue(DensoTable.isValidFloat(1e-12f));
        assertTrue(DensoTable.isValidFloat(-1e12f));
        assertTrue(DensoTable.isValidFloat(9.536743e-7f));
        assertFalse(DensoTable.isValidFloat(1e-13f));
        assertFalse(DensoTable.isValidFloat(1e13f));
        assertFalse(DensoTable.isValidFloat(Float.MIN_VALUE));
        assertFalse(DensoTable.isValidFloat(Float.NaN));
        assertFalse(DensoTable.isValidFloat(Float.NEGATIVE_INFINITY));
    }

    @Test
    public void validateMacParametersMatchesScannerRule() {
        assertNull(DensoTable.validateMacParameters(0.01f, -40f));
        assertNull(DensoTable.validateMacParameters(-0.5f, 0f));
        assertNull(DensoTable.validateMacParameters(1e-9f, 0f));
        assertNull(DensoTable.validateMacParameters(1e7f, 0f));
        assertNotNull(DensoTable.validateMacParameters(0f, 0f));
        assertNotNull(DensoTable.validateMacParameters(1e-13f, 0f));
        assertNotNull(DensoTable.validateMacParameters(1e13f, 0f));
        assertNotNull(DensoTable.validateMacParameters(Float.NaN, 0f));
        assertNotNull(DensoTable.validateMacParameters(1f, 1e-13f));
        assertNotNull(DensoTable.validateMacParameters(1f, Float.POSITIVE_INFINITY));
    }

    @Test
    public void inferredTypeIsMarkedAndCopied() {
        DensoTable1D t = new DensoTable1D();
        t.setDataType(DensoTableType.UINT16);
        assertEquals("UInt16", t.getDataTypeLabel());
        t.setTypeInferred(true);
        assertEquals("UInt16?", t.getDataTypeLabel());
        assertTrue(t.copy().isTypeInferred());
    }

    @Test
    public void macExpressionShowsSignOfOffset() {
        DensoTable1D t = new DensoTable1D();
        assertEquals("", t.getMacExpression());
        t.setHasMAC(true);
        t.setMultiplier(0.5f);
        t.setOffset(-40f);
        assertTrue(t.getMacExpression(), t.getMacExpression().contains("- 40"));
        t.setOffset(3f);
        assertTrue(t.getMacExpression(), t.getMacExpression().contains("+ 3"));
    }

    @Test
    public void copyIsDeepFor1D() {
        DensoTable1D t = new DensoTable1D();
        t.setHeaderAddress(0x1234);
        t.setCountX(2);
        t.setValuesX(new float[] { 1f, 2f });
        t.setValuesY(new double[] { 10, 20 });

        DensoTable1D copy = (DensoTable1D) t.copy();
        copy.getValuesX()[0] = 99f;
        copy.getValuesY()[0] = 99;

        assertEquals(0x1234, copy.getHeaderAddress());
        assertEquals(1f, t.getValuesX()[0], 0);
        assertEquals(10.0, t.getValuesY()[0], 0);
    }

    @Test
    public void copyIsDeepFor2D() {
        DensoTable2D t = new DensoTable2D();
        t.setCountX(2);
        t.setCountY(2);
        t.setValuesY(new float[] { 5f, 6f });
        t.setValuesZ(new double[][] { { 1, 2 }, { 3, 4 } });

        DensoTable2D copy = (DensoTable2D) t.copy();
        copy.setZ(1, 1, 99);
        copy.getValuesY()[1] = 99f;

        assertEquals(4.0, t.getZ(1, 1), 0);
        assertEquals(6f, t.getValuesY()[1], 0);
        assertEquals(99.0, copy.getZ(1, 1), 0);
    }

    @Test
    public void zAccessorsIgnoreOutOfBoundsCells() {
        DensoTable2D t = new DensoTable2D();
        t.setValuesZ(new double[][] { { 1, 2 } });
        t.setZ(5, 5, 7);
        assertEquals(0.0, t.getZ(5, 5), 0);
        assertEquals(1.0, t.getMinZ(), 0);
        assertEquals(2.0, t.getMaxZ(), 0);
    }
}
