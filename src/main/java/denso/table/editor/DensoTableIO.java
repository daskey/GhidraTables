package denso.table.editor;

import java.nio.ByteBuffer;

import denso.table.editor.model.*;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryAccessException;

/** Big-endian table I/O. Reloads are atomic; writes require a caller-owned transaction. */
public final class DensoTableIO {
    private DensoTableIO() {}

    public static void reload(Program program, DensoTable table) throws MemoryAccessException {
        Memory memory = program.getMemory();
        AddressSpace space = program.getAddressFactory().getDefaultAddressSpace();
        // Read every range before publishing any values to the live editor.
        float multiplier = table.getMultiplier();
        float offset = table.getOffset();
        if (table.isHasMAC()) {
            ByteBuffer mac = read(memory, space, macAddress(table), 8);
            multiplier = mac.getFloat();
            offset = mac.getFloat();
        }
        float[] x = floats(read(memory, space, table.getPtrX(), table.getCountX() * 4));
        if (table instanceof DensoTable2D t) {
            float[] y = floats(read(memory, space, t.getPtrY(), t.getCountY() * 4));
            double[] data = values(read(memory, space, t.getPtrZ(),
                    t.getCountX() * t.getCountY() * t.getDataType().getValueSize()), t.getDataType());
            double[][] z = new double[t.getCountY()][t.getCountX()];
            for (int r = 0; r < z.length; r++) {
                System.arraycopy(data, r * t.getCountX(), z[r], 0, t.getCountX());
            }
            t.setValuesY(y);
            t.setValuesZ(z);
        } else {
            DensoTable1D t = (DensoTable1D) table;
            double[] data = values(read(memory, space, t.getPtrY(),
                    t.getCountX() * t.getDataType().getValueSize()), t.getDataType());
            t.setValuesY(data);
        }
        table.setValuesX(x);
        table.setMultiplier(multiplier);
        table.setOffset(offset);
    }

    public static void write(Program program, DensoTable table, boolean dataChanged,
            boolean macChanged) throws MemoryAccessException {
        byte[] data = dataChanged ? encode(table) : null;
        byte[] mac = null;
        if (macChanged && table.isHasMAC()) {
            String error = DensoTable.validateMacParameters(table.getMultiplier(), table.getOffset());
            if (error != null) throw new IllegalArgumentException(error);
            mac = ByteBuffer.allocate(8).putFloat(table.getMultiplier())
                    .putFloat(table.getOffset()).array();
        }
        Memory memory = program.getMemory();
        AddressSpace space = program.getAddressFactory().getDefaultAddressSpace();
        if (data != null) {
            long ptr = table instanceof DensoTable2D t ? t.getPtrZ() : ((DensoTable1D) table).getPtrY();
            memory.setBytes(space.getAddress(ptr), data);
        }
        if (mac != null) memory.setBytes(space.getAddress(macAddress(table)), mac);
    }

    private static long macAddress(DensoTable table) {
        return table.getHeaderAddress() + (table.is2D() ? 20 : 12);
    }

    private static ByteBuffer read(Memory memory, AddressSpace space, long ptr, int size)
            throws MemoryAccessException {
        byte[] bytes = new byte[size];
        if (memory.getBytes(space.getAddress(ptr), bytes) != size) {
            throw new MemoryAccessException("Incomplete read at 0x" + Long.toHexString(ptr));
        }
        return ByteBuffer.wrap(bytes);
    }

    private static float[] floats(ByteBuffer bytes) {
        float[] values = new float[bytes.remaining() / 4];
        for (int i = 0; i < values.length; i++) values[i] = bytes.getFloat();
        return values;
    }

    private static double[] values(ByteBuffer bytes, DensoTableType type) {
        if (!type.isValid()) throw new IllegalArgumentException("Undefined storage type.");
        int size = type.getValueSize();
        double[] values = new double[bytes.remaining() / size];
        for (int i = 0; i < values.length; i++) {
            long bits = 0;
            for (int b = 0; b < size; b++) bits = bits << 8 | bytes.get() & 0xffL;
            values[i] = type.rawToDouble(bits);
        }
        return values;
    }

    static byte[] encode(DensoTable table) {
        DensoTableType type = table.getDataType();
        if (!type.isValid()) throw new IllegalArgumentException("Undefined storage type.");
        int width = table.getCountX();
        int height = table instanceof DensoTable2D t ? t.getCountY() : 1;
        int size = type.getValueSize();
        byte[] bytes = new byte[width * height * size];
        for (int i = 0; i < width * height; i++) {
            double value = table instanceof DensoTable2D t
                    ? t.getZ(i / width, i % width) : ((DensoTable1D) table).getValuesY()[i];
            long bits;
            try {
                bits = type.doubleToRaw(value);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Invalid value at row " + (i / width + 1)
                        + ", column " + (i % width + 1) + ": " + ex.getMessage(), ex);
            }
            for (int b = size - 1; b >= 0; b--) {
                bytes[i * size + b] = (byte) bits;
                bits >>>= 8;
            }
        }
        return bytes;
    }
}
