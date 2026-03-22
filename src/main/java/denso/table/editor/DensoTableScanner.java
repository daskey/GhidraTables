/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor;

import java.util.ArrayList;
import java.util.List;

import denso.table.editor.model.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.*;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

/**
 * Scans a Ghidra {@link Program}'s initialised memory for Denso ECU
 * calibration table headers.
 *
 * <h3>Detection strategy</h3>
 * <ol>
 *   <li>Walk every initialised {@link MemoryBlock} at 4-byte aligned offsets.</li>
 *   <li>At each position attempt to parse a 2-D header first (stricter
 *       validation), then a 1-D header.</li>
 *   <li>Validate that CountX/CountY are in a plausible range and that
 *       pointer values fall inside the loaded memory.</li>
 *   <li>Read axis and data arrays; reject the candidate if any read fails.</li>
 * </ol>
 *
 * <h3>Endianness</h3>
 * Header counts and pointers are <em>big-endian</em>.
 * The TableType field is <em>little-endian</em> (only the first byte matters).
 * Axis arrays (float) and most data types are big-endian.
 */
public final class DensoTableScanner {

    // ------- tunable constants -----------------------------------------------

    /** Minimum plausible axis entry count (1-entry tables are constants, not lookup tables). */
    private static final int MIN_COUNT = 2;
    /** Maximum plausible axis entry count (anything larger is almost certainly garbage). */
    private static final int MAX_COUNT = 250;

    private static final int MIN_POINTER_DISTANCE = 0x100;

    // -------------------------------------------------------------------------

    private DensoTableScanner() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Scans {@code program} for Denso table headers.
     *
     * @param program the loaded ROM to scan
     * @param monitor task monitor (supports cancellation and progress)
     * @return discovered tables in address order; never {@code null}
     * @throws CancelledException if the user cancels
     */
    public static List<DensoTable> scan(Program program, TaskMonitor monitor)
            throws CancelledException {

        List<DensoTable> results = new ArrayList<>();
        Memory memory = program.getMemory();
        AddressSpace space = program.getAddressFactory().getDefaultAddressSpace();

        MemoryBlock[] blocks = memory.getBlocks();

        // Compute total size for progress reporting
        long totalBytes = 0;
        for (MemoryBlock b : blocks) {
            if (b.isInitialized()) totalBytes += b.getSize();
        }
        monitor.initialize(totalBytes);
        monitor.setMessage("Scanning for Denso tables…");

        long scanned = 0;

        for (MemoryBlock block : blocks) {
            monitor.checkCancelled();

            if (!block.isInitialized() || block.isExternalBlock()) {
                continue;
            }

            long blockStartOff = block.getStart().getOffset();
            int  blockSize     = (int) Math.min(block.getSize(), Integer.MAX_VALUE);

            // Read the whole block into a local buffer for fast access
            byte[] buf = new byte[blockSize];
            try {
                memory.getBytes(block.getStart(), buf);
            }
            catch (MemoryAccessException e) {
                scanned += blockSize;
                monitor.setProgress(scanned);
                continue;
            }

            // Walk 4-byte aligned positions
            for (int i = 0; i <= blockSize - 12; i += 4) {
                monitor.checkCancelled();

                // ── Try 2-D first (needs at least 20 bytes for no-MAC, 28 for MAC) ──
                if (i + 20 <= blockSize) {
                    DensoTable2D t2 = tryParse2D(buf, i, blockStartOff, memory, space);
                    if (t2 != null) {
                        results.add(t2);
                        // Skip past the header so we don't double-detect
                        i += (t2.isHasMAC() ? 28 : 20) - 4;
                        continue;
                    }
                }

                // ── Then try 1-D ─────────────────────────────────────────────────
                if (i + 12 <= blockSize) {
                    DensoTable1D t1 = tryParse1D(buf, i, blockStartOff, memory, space);
                    if (t1 != null) {
                        results.add(t1);
                        i += (t1.isHasMAC() ? 20 : 12) - 4;
                    }
                }
            }

            scanned += blockSize;
            monitor.setProgress(scanned);
        }

        return results;
    }

    // =========================================================================
    // Private parsing helpers
    // =========================================================================

    /** Attempts to parse a 2-D table header at {@code buf[offset]}. */
    private static DensoTable2D tryParse2D(byte[] buf, int off,
            long blockStartOff, Memory memory, AddressSpace space) {

        int countX = readInt16BE(buf, off);
        int countY = readInt16BE(buf, off + 2);
        if (!isValidCount(countX) || !isValidCount(countY)) return null;

        long ptrX = readInt32BE(buf, off + 4);
        long ptrY = readInt32BE(buf, off + 8);
        long ptrZ = readInt32BE(buf, off + 12);
        int  typeCode = buf[off + 16] & 0xFF;  // little-endian: first byte only

        // Remaining 3 bytes of the type field must be zero
        if ((buf[off + 17] | buf[off + 18] | buf[off + 19]) != 0) return null;

        if (!DensoTableType.fromCode(typeCode).isValid()) return null;
        DensoTableType dtype = DensoTableType.fromCode(typeCode);

        long headerAddr = blockStartOff + off;
        if (!isValidPointer(ptrX, headerAddr, memory, space)) return null;
        if (!isValidPointer(ptrY, headerAddr, memory, space)) return null;
        if (!isValidPointer(ptrZ, headerAddr, memory, space)) return null;

        // Optional MAC
        boolean hasMAC = false;
        float multiplier = 1.0f, macOffset = 0.0f;
        if (off + 28 <= buf.length) {
            float m = readFloatBE(buf, off + 20);
            float o = readFloatBE(buf, off + 24);
            if (isPlausibleMultiplier(m) && Float.isFinite(o)) {
                hasMAC = true;
                multiplier = m;
                macOffset = o;
            }
        }

        // Read axis arrays — both must be finite and strictly increasing
        float[] valuesX = readFloatArrayFromMemory(memory, space, ptrX, countX);
        if (valuesX == null || !isMonotonicFinite(valuesX)) return null;

        float[] valuesY = readFloatArrayFromMemory(memory, space, ptrY, countY);
        if (valuesY == null || !isMonotonicFinite(valuesY)) return null;

        double[][] valuesZ = readDataMatrix(memory, space, ptrZ, countX, countY, dtype);
        if (valuesZ == null) return null;

        // For float data, raw Z values must be finite and within calibration range
        if (dtype == DensoTableType.FLOAT) {
            for (double[] row : valuesZ) {
                for (double v : row) {
                    if (!Double.isFinite(v) || Math.abs(v) > MAX_AXIS_VALUE) return null;
                }
            }
        }

        // Z data is stored as raw values; the editor applies the MAC when displaying.

        DensoTable2D t = new DensoTable2D();
        t.setHeaderAddress(headerAddr);
        t.setPtrX(ptrX);
        t.setPtrY(ptrY);
        t.setPtrZ(ptrZ);
        t.setCountX(countX);
        t.setCountY(countY);
        t.setDataType(dtype);
        t.setHasMAC(hasMAC);
        t.setMultiplier(multiplier);
        t.setOffset(macOffset);
        t.setValuesX(valuesX);
        t.setValuesY(valuesY);
        t.setValuesZ(valuesZ);
        t.setName("Table2D_0x" + Long.toHexString(headerAddr).toUpperCase());
        return t;
    }

    /** Attempts to parse a 1-D table header at {@code buf[offset]}. */
    private static DensoTable1D tryParse1D(byte[] buf, int off,
            long blockStartOff, Memory memory, AddressSpace space) {

        int countX   = readInt16BE(buf, off);
        int typeCode = buf[off + 2] & 0xFF;   // little-endian: first byte
        if (!isValidCount(countX)) return null;

        // High byte of the 2-byte type field must be zero
        if ((buf[off + 3] & 0xFF) != 0) return null;

        if (!DensoTableType.fromCode(typeCode).isValid()) return null;
        DensoTableType dtype = DensoTableType.fromCode(typeCode);

        long ptrX = readInt32BE(buf, off + 4);
        long ptrY = readInt32BE(buf, off + 8);

        long headerAddr = blockStartOff + off;
        if (!isValidPointer(ptrX, headerAddr, memory, space)) return null;
        if (!isValidPointer(ptrY, headerAddr, memory, space)) return null;

        // Optional MAC
        boolean hasMAC = false;
        float multiplier = 1.0f, macOffset = 0.0f;
        if (off + 20 <= buf.length) {
            float m = readFloatBE(buf, off + 12);
            float o = readFloatBE(buf, off + 16);
            if (isPlausibleMultiplier(m) && Float.isFinite(o)) {
                hasMAC = true;
                multiplier = m;
                macOffset = o;
            }
        }

        // X axis must be finite and strictly increasing
        float[] valuesX = readFloatArrayFromMemory(memory, space, ptrX, countX);
        if (valuesX == null || !isMonotonicFinite(valuesX)) return null;

        double[] valuesY = readDataArray1D(memory, space, ptrY, countX, dtype);
        if (valuesY == null) return null;

        // For float data, raw Y values must be finite and within calibration range
        if (dtype == DensoTableType.FLOAT) {
            for (double v : valuesY) {
                if (!Double.isFinite(v) || Math.abs(v) > MAX_AXIS_VALUE) return null;
            }
        }

        // Y data is stored as raw values; the editor applies the MAC when displaying.

        DensoTable1D t = new DensoTable1D();
        t.setHeaderAddress(headerAddr);
        t.setPtrX(ptrX);
        t.setPtrY(ptrY);
        t.setCountX(countX);
        t.setDataType(dtype);
        t.setHasMAC(hasMAC);
        t.setMultiplier(multiplier);
        t.setOffset(macOffset);
        t.setValuesX(valuesX);
        t.setValuesY(valuesY);
        t.setName("Table1D_0x" + Long.toHexString(headerAddr).toUpperCase());
        return t;
    }

    // =========================================================================
    // Memory I/O
    // =========================================================================

    /**
     * Reads {@code count} big-endian floats starting at the given address.
     * Returns {@code null} on any access error.
     */
    private static float[] readFloatArrayFromMemory(Memory mem, AddressSpace space,
            long ptr, int count) {
        try {
            Address addr = space.getAddress(ptr);
            byte[] raw = new byte[count * 4];
            mem.getBytes(addr, raw);
            float[] out = new float[count];
            for (int i = 0; i < count; i++) {
                out[i] = readFloatBE(raw, i * 4);
            }
            return out;
        }
        catch (Exception e) {
            return null;
        }
    }

    /** Reads a 1-D data array of the given type (big-endian). */
    private static double[] readDataArray1D(Memory mem, AddressSpace space,
            long ptr, int count, DensoTableType dtype) {
        try {
            int elemSize = dtype.getValueSize();
            Address addr = space.getAddress(ptr);
            byte[] raw = new byte[count * elemSize];
            mem.getBytes(addr, raw);
            double[] out = new double[count];
            for (int i = 0; i < count; i++) {
                long rawVal = readBigEndian(raw, i * elemSize, elemSize);
                out[i] = dtype.rawToDouble(rawVal);
            }
            return out;
        }
        catch (Exception e) {
            return null;
        }
    }

    /** Reads a 2-D data matrix (row-major) of the given type (big-endian). */
    private static double[][] readDataMatrix(Memory mem, AddressSpace space,
            long ptr, int countX, int countY, DensoTableType dtype) {
        try {
            int elemSize = dtype.getValueSize();
            Address addr = space.getAddress(ptr);
            byte[] raw = new byte[countX * countY * elemSize];
            mem.getBytes(addr, raw);
            double[][] out = new double[countY][countX];
            for (int y = 0; y < countY; y++) {
                for (int x = 0; x < countX; x++) {
                    int byteOff = (y * countX + x) * elemSize;
                    long rawVal = readBigEndian(raw, byteOff, elemSize);
                    out[y][x] = dtype.rawToDouble(rawVal);
                }
            }
            return out;
        }
        catch (Exception e) {
            return null;
        }
    }

    // =========================================================================
    // Bit-twiddling utilities
    // =========================================================================

    private static int readInt16BE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long readInt32BE(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24)
             | ((b[off + 1] & 0xFFL) << 16)
             | ((b[off + 2] & 0xFFL) << 8)
             |  (b[off + 3] & 0xFFL);
    }

    private static float readFloatBE(byte[] b, int off) {
        return Float.intBitsToFloat((int) readInt32BE(b, off));
    }

    /**
     * Reads {@code size} bytes from {@code buf[off]} as an unsigned big-endian integer.
     */
    private static long readBigEndian(byte[] buf, int off, int size) {
        long v = 0;
        for (int i = 0; i < size; i++) {
            v = (v << 8) | (buf[off + i] & 0xFFL);
        }
        return v;
    }

    // =========================================================================
    // Validation helpers
    // =========================================================================

    /**
     * Returns true for MAC multiplier values that are plausible for ECU calibration.
     * Real multipliers are human-chosen scale factors (e.g. 0.00457, 0.1, 1.0, 2.5, 100).
     * Values outside [1e-5, 1e6] are either subnormal noise or misread ROM bytes.
     */
    private static boolean isPlausibleMultiplier(float v) {
        return Float.isFinite(v) && Math.abs(v) >= 1e-5f && Math.abs(v) <= 1e6f;
    }

    /**
     * Upper bound on any plausible calibration axis value.
     * Legitimate ECU breakpoints (RPM, temperature, pressure, load, …) are always
     * well below this; the large values that slip through from misread ROM pointers
     * (e.g. 4.9e36) are not.
     */
    private static final float MAX_AXIS_VALUE = 1e9f;

    /**
     * Returns true if every value is finite, within a plausible calibration range,
     * not subnormal (unless zero), and the array is strictly increasing.
     * All real calibration axes must satisfy this — random memory almost never will.
     */
    private static boolean isMonotonicFinite(float[] arr) {
        for (int i = 0; i < arr.length; i++) {
            if (!Float.isFinite(arr[i])) return false;
            if (Math.abs(arr[i]) > MAX_AXIS_VALUE) return false;
            // Subnormal non-zero values (|v| < Float.MIN_NORMAL) are misread bytes, not axis breakpoints
            if (arr[i] != 0.0f && Math.abs(arr[i]) < Float.MIN_NORMAL) return false;
            if (i > 0 && arr[i] <= arr[i - 1]) return false;
        }
        return true;
    }

    private static boolean isValidCount(int count) {
        return count >= MIN_COUNT && count <= MAX_COUNT;
    }

    /**
     * Validates that {@code ptr} addresses initialised memory and is at least
     * {@link #MIN_POINTER_DISTANCE} bytes away from {@code headerAddr}.
     */
    private static boolean isValidPointer(long ptr, long headerAddr,
            Memory memory, AddressSpace space) {
        if (Math.abs(ptr - headerAddr) < MIN_POINTER_DISTANCE) return false;
        try {
            return memory.contains(space.getAddress(ptr));
        }
        catch (Exception e) {
            return false;
        }
    }
}
