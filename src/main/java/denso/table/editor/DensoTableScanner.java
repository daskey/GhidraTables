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

    /** Minimum plausible axis entry count. Some valid calibrations are scalar 1x1 maps. */
    private static final int MIN_COUNT = 1;
    /** Maximum plausible axis entry count (anything larger is almost certainly garbage). */
    private static final int MAX_COUNT = 250;

    private static final int MIN_POINTER_DISTANCE = 0x100;
    private static final int[] AMBIGUOUS_1D_NEIGHBOR_OFFSETS = { -20, -12, 12, 20 };
    private static final int[] AMBIGUOUS_2D_NEIGHBOR_OFFSETS = { -28, -20, 20, 28 };
    private static final int LOCAL_DESCRIPTOR_SCAN_BYTES = 0x100;

    // -------------------------------------------------------------------------

    private DensoTableScanner() {}

    private static final class OneDHeaderPattern {
        final int count;
        final int typeCode;
        final long ptrX;
        final long ptrY;

        OneDHeaderPattern(int count, int typeCode, long ptrX, long ptrY) {
            this.count = count;
            this.typeCode = typeCode;
            this.ptrX = ptrX;
            this.ptrY = ptrY;
        }
    }

    private static final class TwoDHeaderPattern {
        final int countX;
        final int countY;
        final int typeCode;
        final long ptrX;
        final long ptrY;
        final long ptrZ;

        TwoDHeaderPattern(int countX, int countY, int typeCode,
                long ptrX, long ptrY, long ptrZ) {
            this.countX = countX;
            this.countY = countY;
            this.typeCode = typeCode;
            this.ptrX = ptrX;
            this.ptrY = ptrY;
            this.ptrZ = ptrZ;
        }
    }

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

        // Read axis arrays — both must be finite and ordered
        float[] valuesX = readFloatArrayFromMemory(memory, space, ptrX, countX);
        if (valuesX == null || !isMonotonicFinite(valuesX)) return null;

        float[] valuesY = readFloatArrayFromMemory(memory, space, ptrY, countY);
        if (valuesY == null || !isMonotonicFinite(valuesY)) return null;

        DensoTableType dtype = resolve2DDataType(buf, off, blockStartOff,
                countX, countY, typeCode, ptrX, ptrY, ptrZ, hasMAC, memory, space);
        if (dtype == null) return null;

        double[][] valuesZ = readDataMatrix(memory, space, ptrZ, countX, countY, dtype);
        if (valuesZ == null) return null;

        // For float data, raw Z values must be finite and within calibration range
        if (dtype == DensoTableType.FLOAT) {
            for (double[] row : valuesZ) {
                for (double v : row) {
                    if (!isPlausibleCalibrationFloat(v)) return null;
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

    private static DensoTableType resolve2DDataType(byte[] buf, int off,
            long blockStartOff, int countX, int countY, int typeCode,
            long ptrX, long ptrY, long ptrZ, boolean hasMAC,
            Memory memory, AddressSpace space) {
        DensoTableType declared = DensoTableType.fromCode(typeCode);
        if (!declared.isValid()) {
            return null;
        }

        if (!hasMAC && typeCode == DensoTableType.FLOAT.getCode()) {
            DensoTableType inferred = inferPacked2DDataType(buf, off, blockStartOff,
                    countX, countY, ptrX, ptrY, ptrZ, memory, space);
            if (inferred != null) {
                return inferred;
            }
        }

        return declared;
    }

    private static DensoTableType inferPacked2DDataType(byte[] buf, int off,
            long blockStartOff, int countX, int countY, long ptrX, long ptrY, long ptrZ,
            Memory memory, AddressSpace space) {
        long nextPtr = findNextLocalDataPointer(buf, off, blockStartOff, ptrZ,
                memory, space);
        if (nextPtr == Long.MAX_VALUE) {
            return null;
        }

        long zStride = nextPtr - ptrZ;
        if (matchesCompactPayloadStride(zStride, countX * countY)) {
            return DensoTableType.UINT8;
        }
        if (matchesCompactPayloadStride(zStride,
                countX * countY * DensoTableType.UINT16.getValueSize())) {
            return DensoTableType.UINT16;
        }
        return null;
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

        // X axis must be finite and ordered
        float[] valuesX = readFloatArrayFromMemory(memory, space, ptrX, countX);
        if (valuesX == null || !isMonotonicFinite(valuesX)) return null;

        DensoTableType dtype = resolve1DDataType(buf, off, blockStartOff, countX,
                typeCode, ptrX, ptrY, hasMAC, memory, space);
        if (dtype == null) return null;

        double[] valuesY = readDataArray1D(memory, space, ptrY, countX, dtype);
        if (valuesY == null) return null;

        // For float data, raw Y values must be finite and within calibration range
        if (dtype == DensoTableType.FLOAT) {
            for (double v : valuesY) {
                if (!isPlausibleCalibrationFloat(v)) return null;
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

    private static DensoTableType resolve1DDataType(byte[] buf, int off,
            long blockStartOff, int countX, int typeCode, long ptrX, long ptrY,
            boolean hasMAC, Memory memory, AddressSpace space) {
        DensoTableType declared = DensoTableType.fromCode(typeCode);
        if (!declared.isValid()) {
            return null;
        }

        // Some no-MAC 1-D descriptors use a compact payload layout where 0x00 does
        // not actually mean 4-byte float data. Infer that from neighboring headers
        // and pointer spacing before falling back to the nominal float decode.
        if (!hasMAC && typeCode == DensoTableType.FLOAT.getCode()) {
            DensoTableType inferred = inferPacked1DDataType(buf, off, blockStartOff,
                    countX, ptrX, ptrY, memory, space);
            if (inferred != null) {
                return inferred;
            }

            if (hasWeakFloatPayload(memory, space, ptrY, countX)) {
                DensoTableType aligned = inferAlignedIntegerDataType(memory, space,
                        ptrY, countX);
                if (aligned != null) {
                    return aligned;
                }
            }
        }

        return declared;
    }

    private static DensoTableType inferPacked1DDataType(byte[] buf, int off,
            long blockStartOff, int countX, long ptrX, long ptrY,
            Memory memory, AddressSpace space) {
        long nextPtr = findNextLocalDataPointer(buf, off, blockStartOff, ptrY,
                memory, space);
        long yStride = nextPtr == Long.MAX_VALUE ? Long.MAX_VALUE : nextPtr - ptrY;
        if (matchesCompactPayloadStride(yStride, countX)) {
            return DensoTableType.UINT8;
        }
        if (matchesCompactPayloadStride(yStride,
                countX * DensoTableType.UINT16.getValueSize())) {
            return DensoTableType.UINT16;
        }

        for (int delta : AMBIGUOUS_1D_NEIGHBOR_OFFSETS) {
            OneDHeaderPattern sibling = read1DHeaderPattern(buf, off + delta,
                    blockStartOff, memory, space);
            if (sibling == null || sibling.count != countX) {
                continue;
            }

            long xStride = Math.abs(sibling.ptrX - ptrX);
            if (xStride != countX * 4L) {
                continue;
            }

            long siblingYStride = Math.abs(sibling.ptrY - ptrY);
            DensoTableType siblingType = DensoTableType.fromCode(sibling.typeCode);

            if (siblingType.isValid()
                    && sibling.typeCode != DensoTableType.FLOAT.getCode()
                    && siblingYStride == countX * (long) siblingType.getValueSize()) {
                return siblingType;
            }

            if (siblingYStride == countX) {
                return DensoTableType.UINT8;
            }
            if (siblingYStride == countX * 2L) {
                return DensoTableType.UINT16;
            }
        }

        return null;
    }

    private static boolean hasWeakFloatPayload(Memory memory, AddressSpace space,
            long ptrY, int countX) {
        double[] valuesY = readDataArray1D(memory, space, ptrY, countX,
                DensoTableType.FLOAT);
        if (valuesY == null) {
            return true;
        }

        int plausibleCount = 0;
        int longestRun = 0;
        int currentRun = 0;
        for (double v : valuesY) {
            if (isPlausibleCalibrationFloat(v)) {
                plausibleCount++;
                currentRun++;
                longestRun = Math.max(longestRun, currentRun);
            }
            else {
                currentRun = 0;
            }
        }

        return plausibleCount <= countX / 4 && longestRun < 3;
    }

    private static DensoTableType inferAlignedIntegerDataType(Memory memory,
            AddressSpace space, long ptrY, int countX) {
        if (hasAlignedIntegerSlots(memory, space, ptrY, countX,
                DensoTableType.UINT16.getValueSize())) {
            return DensoTableType.UINT16;
        }
        if (hasAlignedIntegerSlots(memory, space, ptrY, countX,
                DensoTableType.UINT8.getValueSize())) {
            return DensoTableType.UINT8;
        }
        return null;
    }

    private static boolean hasAlignedIntegerSlots(Memory memory, AddressSpace space,
            long ptr, int count, int elemSize) {
        int payloadSize = count * elemSize;
        int alignedSize = alignTo4(payloadSize);
        if (alignedSize == payloadSize) {
            return false;
        }

        int slotsToCheck = 2;
        byte[] raw = readBytesFromMemory(memory, space, ptr, alignedSize * slotsToCheck);
        if (raw == null) {
            raw = readBytesFromMemory(memory, space, ptr, alignedSize);
            slotsToCheck = raw == null ? 0 : 1;
        }
        if (raw == null || slotsToCheck == 0) {
            return false;
        }

        int paddingOffset = payloadSize;
        int paddingLength = alignedSize - payloadSize;
        boolean sawNonZeroValue = false;

        for (int slot = 0; slot < slotsToCheck; slot++) {
            int slotOff = slot * alignedSize;
            for (int i = 0; i < payloadSize; i++) {
                if (raw[slotOff + i] != 0) {
                    sawNonZeroValue = true;
                    break;
                }
            }
            for (int i = 0; i < paddingLength; i++) {
                if (raw[slotOff + paddingOffset + i] != 0) {
                    return false;
                }
            }
        }

        return sawNonZeroValue;
    }

    private static int alignTo4(int size) {
        return (size + 3) & ~3;
    }

    private static boolean matchesCompactPayloadStride(long actualStride,
            int payloadSize) {
        return actualStride == payloadSize || actualStride == alignTo4(payloadSize);
    }

    private static long findNextLocalDataPointer(byte[] buf, int off,
            long blockStartOff, long ptr, Memory memory, AddressSpace space) {
        long nextPtr = Long.MAX_VALUE;

        for (int delta : AMBIGUOUS_1D_NEIGHBOR_OFFSETS) {
            nextPtr = Math.min(nextPtr, nextHigherLocalDataPointer(buf, off + delta,
                    blockStartOff, ptr, memory, space));
        }
        for (int delta : AMBIGUOUS_2D_NEIGHBOR_OFFSETS) {
            nextPtr = Math.min(nextPtr, nextHigherLocalDataPointer(buf, off + delta,
                    blockStartOff, ptr, memory, space));
        }

        int scanStart = Math.max(0, off - LOCAL_DESCRIPTOR_SCAN_BYTES);
        int scanEnd = Math.min(buf.length - 12, off + LOCAL_DESCRIPTOR_SCAN_BYTES);
        for (int neighborOff = scanStart; neighborOff <= scanEnd; neighborOff += 4) {
            nextPtr = Math.min(nextPtr, nextHigherLocalDataPointer(buf, neighborOff,
                    blockStartOff, ptr, memory, space));
        }

        return nextPtr;
    }

    private static long nextHigherLocalDataPointer(byte[] buf, int off,
            long blockStartOff, long ptr, Memory memory, AddressSpace space) {
        long nextPtr = Long.MAX_VALUE;

        OneDHeaderPattern oneD = read1DHeaderPattern(buf, off, blockStartOff,
                memory, space);
        if (oneD != null) {
            if (oneD.ptrX > ptr) {
                nextPtr = Math.min(nextPtr, oneD.ptrX);
            }
            if (oneD.ptrY > ptr) {
                nextPtr = Math.min(nextPtr, oneD.ptrY);
            }
        }

        TwoDHeaderPattern twoD = read2DHeaderPattern(buf, off, blockStartOff,
                memory, space);
        if (twoD != null) {
            if (twoD.ptrX > ptr) {
                nextPtr = Math.min(nextPtr, twoD.ptrX);
            }
            if (twoD.ptrY > ptr) {
                nextPtr = Math.min(nextPtr, twoD.ptrY);
            }
            if (twoD.ptrZ > ptr) {
                nextPtr = Math.min(nextPtr, twoD.ptrZ);
            }
        }

        return nextPtr;
    }

    private static OneDHeaderPattern read1DHeaderPattern(byte[] buf, int off,
            long blockStartOff, Memory memory, AddressSpace space) {
        if (off < 0 || off + 12 > buf.length) {
            return null;
        }

        int countX = readInt16BE(buf, off);
        int typeCode = buf[off + 2] & 0xFF;
        if (!isValidCount(countX) || (buf[off + 3] & 0xFF) != 0) {
            return null;
        }

        if (!DensoTableType.fromCode(typeCode).isValid()) {
            return null;
        }

        long ptrX = readInt32BE(buf, off + 4);
        long ptrY = readInt32BE(buf, off + 8);
        long headerAddr = blockStartOff + off;
        if (!isValidPointer(ptrX, headerAddr, memory, space)
                || !isValidPointer(ptrY, headerAddr, memory, space)) {
            return null;
        }

        float[] valuesX = readFloatArrayFromMemory(memory, space, ptrX, countX);
        if (valuesX == null || !isMonotonicFinite(valuesX)) {
            return null;
        }

        return new OneDHeaderPattern(countX, typeCode, ptrX, ptrY);
    }

    private static TwoDHeaderPattern read2DHeaderPattern(byte[] buf, int off,
            long blockStartOff, Memory memory, AddressSpace space) {
        if (off < 0 || off + 20 > buf.length) {
            return null;
        }

        int countX = readInt16BE(buf, off);
        int countY = readInt16BE(buf, off + 2);
        int typeCode = buf[off + 16] & 0xFF;
        if (!isValidCount(countX) || !isValidCount(countY)) {
            return null;
        }
        if ((buf[off + 17] | buf[off + 18] | buf[off + 19]) != 0) {
            return null;
        }
        if (!DensoTableType.fromCode(typeCode).isValid()) {
            return null;
        }

        long ptrX = readInt32BE(buf, off + 4);
        long ptrY = readInt32BE(buf, off + 8);
        long ptrZ = readInt32BE(buf, off + 12);
        long headerAddr = blockStartOff + off;
        if (!isValidPointer(ptrX, headerAddr, memory, space)
                || !isValidPointer(ptrY, headerAddr, memory, space)
                || !isValidPointer(ptrZ, headerAddr, memory, space)) {
            return null;
        }

        float[] valuesX = readFloatArrayFromMemory(memory, space, ptrX, countX);
        float[] valuesY = readFloatArrayFromMemory(memory, space, ptrY, countY);
        if (valuesX == null || valuesY == null
                || !isMonotonicFinite(valuesX) || !isMonotonicFinite(valuesY)) {
            return null;
        }

        return new TwoDHeaderPattern(countX, countY, typeCode, ptrX, ptrY, ptrZ);
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

    private static byte[] readBytesFromMemory(Memory mem, AddressSpace space,
            long ptr, int size) {
        try {
            Address addr = space.getAddress(ptr);
            byte[] raw = new byte[size];
            mem.getBytes(addr, raw);
            return raw;
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
     * Real multipliers are human-chosen scale factors (e.g. 9.536743e-7, 0.00457,
     * 0.1, 1.0, 2.5, 100). Values outside [1e-7, 1e6] are either noise or
     * misread ROM bytes.
     */
    private static boolean isPlausibleMultiplier(float v) {
        return DensoTable.validateMacParameters(v, 0.0f) == null;
    }

    /**
     * Upper bound on any plausible calibration float.
     * Legitimate ECU breakpoints and table values (RPM, temperature, pressure, load, …)
     * are always well below this; the huge values that slip through from misread ROM
     * bytes (e.g. 4.9e36) are not.
     */
    private static final float MAX_AXIS_VALUE = 1e9f;

    /**
     * Minimum magnitude for a meaningful non-zero calibration float.
     * Values down near 1e-38 commonly come from byte-filled arrays such as 0x01010101
     * or 0x02020202 being misread as floats, not from real calibration data.
     */
    private static final double MIN_NON_ZERO_CALIBRATION_FLOAT = 1e-20;

    /**
     * Returns true if every value is finite, within a plausible calibration range,
     * not subnormal (unless zero), and the array is monotonic non-decreasing.
     * Duplicate breakpoints do occur in real calibrations, so only descending axes
     * are rejected.
     */
    private static boolean isMonotonicFinite(float[] arr) {
        for (int i = 0; i < arr.length; i++) {
            if (!isPlausibleCalibrationFloat(arr[i])) return false;
            if (i > 0 && arr[i] < arr[i - 1]) return false;
        }
        return true;
    }

    private static boolean isPlausibleCalibrationFloat(double v) {
        if (!Double.isFinite(v)) return false;
        if (Math.abs(v) > MAX_AXIS_VALUE) return false;
        if (v == 0.0) return true;
        // Subnormal and vanishingly small normals are almost always misread bytes.
        return Math.abs(v) >= Float.MIN_NORMAL
                && Math.abs(v) >= MIN_NON_ZERO_CALIBRATION_FLOAT;
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
