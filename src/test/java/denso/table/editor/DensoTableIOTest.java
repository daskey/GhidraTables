package denso.table.editor;

import denso.table.editor.model.*;
import org.junit.Test;
import static org.junit.Assert.*;
import ghidra.program.model.mem.MemoryAccessException;

public class DensoTableIOTest {
    private DensoTable1D table(DensoTableType type) {
        DensoTable1D t = new DensoTable1D();
        t.setHeaderAddress(0x1100);
        t.setCountX(2);
        t.setPtrX(0x1800);
        t.setPtrY(0x1900);
        t.setDataType(type);
        t.setValuesX(new float[] {1, 2});
        t.setValuesY(new double[] {3, 4});
        return t;
    }

    @Test public void encodesSignedAndUnsignedBigEndian() {
        DensoTable1D t = table(DensoTableType.INT16);
        t.setValuesY(new double[] {-32768, -1});
        assertArrayEquals(new byte[] {(byte) 0x80, 0, (byte) 0xff, (byte) 0xff}, DensoTableIO.encode(t));
        t.setDataType(DensoTableType.UINT32);
        t.setValuesY(new double[] {4294967295d, 0x12345678});
        assertArrayEquals(new byte[] {-1,-1,-1,-1,0x12,0x34,0x56,0x78}, DensoTableIO.encode(t));
    }

    @Test public void saveAndReloadQuantizeEverySupportedType() throws Exception {
        for (DensoTableType type : DensoTableType.values()) {
            if (!type.isValid()) continue;
            TestMemory fixture = new TestMemory();
            fixture.add(0x1000, 0x1000).at(0x1800).putFloat(1).putFloat(2);
            DensoTable1D t = table(type);
            t.setValuesY(new double[] {type.minimumValue(), type.maximumValue()});
            DensoTableIO.write(fixture.program, t, true, false);
            t.setValuesY(new double[] {0, 0});
            DensoTableIO.reload(fixture.program, t);
            assertArrayEquals(new double[] {type.minimumValue(), type.maximumValue()}, t.getValuesY(), 0);
        }
    }

    @Test public void failedReloadRetainsDataAxesAndMacTogether() {
        TestMemory fixture = new TestMemory();
        TestMemory.Region block = fixture.add(0x1000, 0x1000);
        block.at(0x110c).putFloat(2).putFloat(40);
        block.at(0x1800).putFloat(10).putFloat(20);
        fixture.failReadAt = 0x1900;
        DensoTable1D t = table(DensoTableType.FLOAT);
        t.setHasMAC(true);
        assertThrows(MemoryAccessException.class, () -> DensoTableIO.reload(fixture.program, t));
        assertArrayEquals(new float[] {1, 2}, t.getValuesX(), 0);
        assertArrayEquals(new double[] {3, 4}, t.getValuesY(), 0);
        assertEquals(1, t.getMultiplier(), 0);
        assertEquals(0, t.getOffset(), 0);
    }

    @Test public void shortReadIsAnErrorInsteadOfZeroPadding() {
        TestMemory fixture = new TestMemory();
        fixture.add(0x1000, 0x904);
        DensoTable1D t = table(DensoTableType.FLOAT);
        assertThrows(MemoryAccessException.class, () -> DensoTableIO.reload(fixture.program, t));
        assertArrayEquals(new double[] {3, 4}, t.getValuesY(), 0);
    }

    @Test public void macOnlyWritePreservesPayload() throws Exception {
        TestMemory fixture = new TestMemory();
        TestMemory.Region block = fixture.add(0x1000, 0x1000);
        block.at(0x1900).putFloat(99).putFloat(100);
        DensoTable1D t = table(DensoTableType.FLOAT);
        t.setHasMAC(true);
        t.setMultiplier(-2);
        t.setOffset(40);
        DensoTableIO.write(fixture.program, t, false, true);
        assertEquals(99, block.at(0x1900).getFloat(), 0);
        assertEquals(-2, block.at(0x110c).getFloat(), 0);
        assertEquals(40, block.at(0x1110).getFloat(), 0);
    }

    @Test public void matrixUsesRowMajorStorage() throws Exception {
        TestMemory fixture = new TestMemory();
        fixture.add(0x1000, 0x1000);
        DensoTable2D t = new DensoTable2D();
        t.setCountX(2); t.setCountY(2);
        t.setPtrX(0x1700); t.setPtrY(0x1800); t.setPtrZ(0x1900);
        t.setDataType(DensoTableType.UINT16);
        t.setValuesZ(new double[][] {{1, 2}, {3, 4}});
        DensoTableIO.write(fixture.program, t, true, false);
        DensoTableIO.reload(fixture.program, t);
        assertArrayEquals(new double[] {1, 2}, t.getValuesZ()[0], 0);
        assertArrayEquals(new double[] {3, 4}, t.getValuesZ()[1], 0);
    }
}
