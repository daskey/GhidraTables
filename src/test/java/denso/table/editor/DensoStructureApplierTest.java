/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor;

import static org.junit.Assert.*;

import org.junit.Test;

import denso.table.editor.model.DensoTable;
import denso.table.editor.model.DensoTable1D;
import denso.table.editor.model.DensoTable2D;
import ghidra.program.model.data.*;

public class DensoStructureApplierTest {

    private static Structure header(DensoTable table, boolean mac) {
        table.setHasMAC(mac);
        return DensoStructureApplier.buildHeaderStruct(table);
    }

    private static DataTypeComponent field(Structure s, String name) {
        for (DataTypeComponent c : s.getDefinedComponents()) {
            if (name.equals(c.getFieldName())) {
                return c;
            }
        }
        fail(s.getName() + " has no field " + name);
        return null;
    }

    private static void assertField(Structure s, String name, int offset, int length) {
        DataTypeComponent c = field(s, name);
        assertEquals(s.getName() + "." + name + " offset", offset, c.getOffset());
        assertEquals(s.getName() + "." + name + " length", length, c.getLength());
    }

    /** The type code is the single signed byte the lookup code tests. */
    private static void assertTypeCodeByte(Structure s, int offset) {
        assertField(s, "tableType", offset, 1);
        assertTrue(field(s, "tableType").getDataType().isEquivalent(SignedByteDataType.dataType));
    }

    private static void assertFloatPointer(Structure s, String name) {
        DataType type = field(s, name).getDataType();
        assertTrue(s.getName() + "." + name + " is a pointer", type instanceof Pointer);
        assertEquals(4, type.getLength());
        assertTrue(s.getName() + "." + name + " points to float",
                FloatDataType.dataType.isEquivalent(((Pointer) type).getDataType()));
    }

    private static void assertUntypedPointer(Structure s, String name) {
        DataType type = field(s, name).getDataType();
        assertTrue(s.getName() + "." + name + " is a pointer", type instanceof Pointer);
        assertEquals(4, type.getLength());
        assertNull(s.getName() + "." + name + " has no element type",
                ((Pointer) type).getDataType());
    }

    @Test
    public void twoDimensionalHeadersMatchTheLookupLayout() {
        for (boolean mac : new boolean[] { false, true }) {
            Structure s = header(new DensoTable2D(), mac);
            assertEquals(mac ? "DensoTable2DHeaderMacBE" : "DensoTable2DHeaderNoMacBE", s.getName());
            assertEquals(mac ? 28 : 20, s.getLength());
            assertField(s, "countX", 0, 2);
            assertField(s, "countY", 2, 2);
            assertField(s, "ptrX", 4, 4);
            assertFloatPointer(s, "ptrX");
            assertField(s, "ptrY", 8, 4);
            assertFloatPointer(s, "ptrY");
            assertField(s, "ptrZ", 12, 4);
            assertUntypedPointer(s, "ptrZ");
            assertTypeCodeByte(s, 16);
            assertField(s, "reserved", 17, 3);
            if (mac) {
                assertField(s, "multiplier", 20, 4);
                assertField(s, "offset", 24, 4);
            }
        }
    }

    @Test
    public void oneDimensionalHeadersMatchTheLookupLayout() {
        for (boolean mac : new boolean[] { false, true }) {
            Structure s = header(new DensoTable1D(), mac);
            assertEquals(mac ? "DensoTable1DHeaderMacBE" : "DensoTable1DHeaderNoMacBE", s.getName());
            assertEquals(mac ? 20 : 12, s.getLength());
            assertField(s, "countX", 0, 2);
            assertTypeCodeByte(s, 2);
            assertField(s, "reserved", 3, 1);
            assertField(s, "ptrX", 4, 4);
            assertFloatPointer(s, "ptrX");
            assertField(s, "ptrY", 8, 4);
            assertUntypedPointer(s, "ptrY");
            if (mac) {
                assertField(s, "multiplier", 12, 4);
                assertField(s, "offset", 16, 4);
            }
        }
    }
}
