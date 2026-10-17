/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor;

import java.util.ArrayList;
import java.util.List;

import denso.table.editor.model.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSetView;
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
 * Record and axis validation follows ScoobyRom
 * (<a href="https://github.com/aalesv/ScoobyRom">github.com/aalesv/ScoobyRom</a>),
 * where its "2D"/"3D" tables are this extension's 1-D/2-D tables:
 * <ol>
 *   <li>Walk every initialised block in the default address space at 4-byte
 *       aligned offsets, trying a 2-D record first, then a 1-D record.</li>
 *   <li>Axis counts must be 2..255 and the type field a known type code with
 *       its unused bytes zero.</li>
 *   <li>Pointers must lie at least 8 KiB into the ROM, inside initialised
 *       memory, differ from each other, and the axis/data ranges must not
 *       overlap (data is sized as 1 byte per value for this check, since the
 *       declared type can be wrong).</li>
 *   <li>Axis arrays must be non-decreasing and every value must be 0 or have a
 *       magnitude in [1e-12, 1e12].</li>
 *   <li>A MAC pair is present when the multiplier is non-zero and both floats
 *       pass the same value check.</li>
 *   <li>Table data never causes a record to be rejected. Only the data type is
 *       refined: records that declare float data without a MAC are checked for
 *       a packed 8/16-bit payload using neighbouring records, and otherwise read
 *       as UInt16 when the payload is not valid floats. Such types are flagged
 *       as inferred.</li>
 * </ol>
 *
 * <h3>Endianness</h3>
 * Header counts and pointers are <em>big-endian</em>.
 * The TableType field is <em>little-endian</em> (only the first byte matters).
 * Axis arrays (float) and most data types are big-endian.
 */
public final class DensoTableScanner {

    // ------- tunable constants -----------------------------------------------

    /** Minimum axis entry count (ScoobyRom: counts below 2 do not make sense). */
    static final int MIN_COUNT = 2;
    /** Maximum axis entry count. */
    static final int MAX_COUNT = 255;

    /**
     * Pointers into the first 8 KiB of the ROM are rejected: that area holds
     * vectors and low-level code, not maps (ScoobyRom's {@code PosMin}).
     */
    static final int MIN_POINTER_OFFSET = 8 * 1024;

    private static final int[] AMBIGUOUS_1D_NEIGHBOR_OFFSETS = { -20, -12, 12, 20 };
    private static final int LOCAL_DESCRIPTOR_SCAN_BYTES = 0x100;
    /** How often (in bytes) progress is reported while walking a block. */
    private static final int PROGRESS_INTERVAL_BYTES = 0x1000;

    // -------------------------------------------------------------------------

    private DensoTableScanner() {}

    /** Memory views shared by every parse in one scan. */
    private record ScanContext(Memory memory, AddressSpace space,
            AddressSetView initialized, long minPointer) {

        /** True when {@code [ptr, ptr + length)} is initialised memory at or above the minimum. */
        boolean isReadable(long ptr, long length) {
            if (ptr < minPointer || length <= 0) return false;
            try {
                Address start = space.getAddress(ptr);
                Address end = space.getAddress(ptr + length - 1);
                return initialized.contains(start, end);
            }
            catch (Exception e) {
                return false;
            }
        }
    }

    private record OneDHeaderPattern(int count, int typeCode, long ptrX, long ptrY) {}

    private record TwoDHeaderPattern(int countX, int countY, int typeCode,
            long ptrX, long ptrY, long ptrZ) {}

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

        // Compute total size for progress reporting, and the lowest ROM address
        long totalBytes = 0;
        long romStart = Long.MAX_VALUE;
        for (MemoryBlock b : blocks) {
            if (isScannable(b, space)) {
                totalBytes += b.getSize();
                romStart = Math.min(romStart, b.getStart().getOffset());
            }
        }
        if (romStart == Long.MAX_VALUE) {
            return results;
        }
        ScanContext ctx = new ScanContext(memory, space,
                memory.getAllInitializedAddressSet(), romStart + MIN_POINTER_OFFSET);

        monitor.initialize(totalBytes);
        monitor.setMessage("Scanning for Denso tables…");

        long scanned = 0;

        for (MemoryBlock block : blocks) {
            monitor.checkCancelled();

            // Header addresses and pointers are resolved in the default address
            // space everywhere else (navigation, write-back, structure apply), so
            // a header found in an overlay or other space would map to the wrong
            // bytes. Only scan blocks that live in the default space.
            if (!isScannable(block, space)) {
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
                if (i % PROGRESS_INTERVAL_BYTES == 0) {
                    monitor.setProgress(scanned + i);
                }

                // ── Try 2-D first (needs at least 20 bytes for no-MAC, 28 for MAC) ──
                if (i + 20 <= blockSize) {
                    DensoTable2D t2 = tryParse2D(buf, i, blockStartOff, ctx);
                    if (t2 != null) {
                        results.add(t2);
                        // Skip past the header so we don't double-detect
                        i += (t2.isHasMAC() ? 28 : 20) - 4;
                        continue;
                    }
                }

                // ── Then try 1-D ─────────────────────────────────────────────────
                DensoTable1D t1 = tryParse1D(buf, i, blockStartOff, ctx);
                if (t1 != null) {
                    results.add(t1);
                    i += (t1.isHasMAC() ? 20 : 12) - 4;
                }
            }

            scanned += blockSize;
            monitor.setProgress(scanned);
        }

        return results;
    }

    private static boolean isScannable(MemoryBlock block, AddressSpace space) {
        return block.isInitialized()
                && !block.isExternalBlock()
                && block.getStart().getAddressSpace().equals(space);
    }

    /**
     * Describes initialized blocks that {@link #scan} skips because they are not
     * in the default address space (overlays, other spaces), e.g.
     * {@code "cal (overlay space cal, 256 KB)"}. Empty when nothing is skipped.
     */
    public static List<String> describeSkippedBlocks(Program program) {
        AddressSpace space = program.getAddressFactory().getDefaultAddressSpace();
        List<String> skipped = new ArrayList<>();
        for (MemoryBlock block : program.getMemory().getBlocks()) {
            if (block.isInitialized() && !block.isExternalBlock() && !isScannable(block, space)) {
                AddressSpace blockSpace = block.getStart().getAddressSpace();
                skipped.add(String.format("%s (%s space %s, %d KB)", block.getName(),
                        blockSpace.isOverlaySpace() ? "overlay" : "non-default",
                        blockSpace.getName(), Math.max(1, block.getSize() / 1024)));
            }
        }
        return skipped;
    }

    // =========================================================================
    // Record parsing
    // =========================================================================

    /** Attempts to parse a 2-D table header at {@code buf[off]}. */
    private static DensoTable2D tryParse2D(byte[] buf, int off, long blockStartOff,
            ScanContext ctx) {

        TwoDHeaderPattern hdr = read2DHeaderPattern(buf, off, ctx);
        if (hdr == null) return null;

        int countX = hdr.countX();
        int countY = hdr.countY();

        // Optional MAC
        boolean hasMAC = false;
        float multiplier = 1.0f, macOffset = 0.0f;
        if (off + 28 <= buf.length) {
            float m = readFloatBE(buf, off + 20);
            float o = readFloatBE(buf, off + 24);
            if (DensoTable.validateMacParameters(m, o) == null) {
                hasMAC = true;
                multiplier = m;
                macOffset = o;
            }
        }

        float[] valuesX = readFloatArrayFromMemory(ctx, hdr.ptrX(), countX);
        float[] valuesY = readFloatArrayFromMemory(ctx, hdr.ptrY(), countY);
        if (valuesX == null || valuesY == null) return null;

        DensoTableType declared = DensoTableType.fromCode(hdr.typeCode());
        DensoTableType dtype = declared;
        if (!hasMAC && declared == DensoTableType.FLOAT) {
            DensoTableType packed = inferPacked2DDataType(buf, off, blockStartOff,
                    hdr.ptrZ(), countX, countY, ctx);
            if (packed != null) {
                dtype = packed;
            }
            else if (!hasValidFloatPayload(ctx, hdr.ptrZ(), countX * countY)) {
                // ScoobyRom: float without MAC whose values aren't valid floats is UInt16
                dtype = DensoTableType.UINT16;
            }
        }

        double[][] valuesZ = readDataMatrix(ctx, hdr.ptrZ(), countX, countY, dtype);
        if (valuesZ == null) return null;

        long headerAddr = blockStartOff + off;
        DensoTable2D t = new DensoTable2D();
        t.setHeaderAddress(headerAddr);
        t.setPtrX(hdr.ptrX());
        t.setPtrY(hdr.ptrY());
        t.setPtrZ(hdr.ptrZ());
        t.setCountX(countX);
        t.setCountY(countY);
        t.setDataType(dtype);
        t.setTypeInferred(dtype != declared);
        t.setHasMAC(hasMAC);
        t.setMultiplier(multiplier);
        t.setOffset(macOffset);
        t.setValuesX(valuesX);
        t.setValuesY(valuesY);
        t.setValuesZ(valuesZ);
        t.setName("Table2D_0x" + Long.toHexString(headerAddr).toUpperCase());
        return t;
    }

    /** Attempts to parse a 1-D table header at {@code buf[off]}. */
    private static DensoTable1D tryParse1D(byte[] buf, int off, long blockStartOff,
            ScanContext ctx) {

        OneDHeaderPattern hdr = read1DHeaderPattern(buf, off, ctx);
        if (hdr == null) return null;

        int countX = hdr.count();

        // Optional MAC
        boolean hasMAC = false;
        float multiplier = 1.0f, macOffset = 0.0f;
        if (off + 20 <= buf.length) {
            float m = readFloatBE(buf, off + 12);
            float o = readFloatBE(buf, off + 16);
            if (DensoTable.validateMacParameters(m, o) == null) {
                hasMAC = true;
                multiplier = m;
                macOffset = o;
            }
        }

        float[] valuesX = readFloatArrayFromMemory(ctx, hdr.ptrX(), countX);
        if (valuesX == null) return null;

        DensoTableType declared = DensoTableType.fromCode(hdr.typeCode());
        DensoTableType dtype = declared;
        if (!hasMAC && declared == DensoTableType.FLOAT) {
            // Some no-MAC 1-D descriptors use a compact payload layout where 0x00
            // does not actually mean 4-byte float data. Infer that from
            // neighboring headers and pointer spacing first.
            DensoTableType packed = inferPacked1DDataType(buf, off, blockStartOff,
                    countX, hdr.ptrX(), hdr.ptrY(), ctx);
            if (packed != null) {
                dtype = packed;
            }
            else if (!hasValidFloatPayload(ctx, hdr.ptrY(), countX)) {
                // ScoobyRom: float without MAC whose values aren't valid floats is
                // UInt16; zero padding after the payload can show it is UInt8.
                DensoTableType aligned = inferAlignedIntegerDataType(ctx, hdr.ptrY(), countX);
                dtype = aligned != null ? aligned : DensoTableType.UINT16;
            }
        }

        double[] valuesY = readDataArray1D(ctx, hdr.ptrY(), countX, dtype);
        if (valuesY == null) return null;

        long headerAddr = blockStartOff + off;
        DensoTable1D t = new DensoTable1D();
        t.setHeaderAddress(headerAddr);
        t.setPtrX(hdr.ptrX());
        t.setPtrY(hdr.ptrY());
        t.setCountX(countX);
        t.setDataType(dtype);
        t.setTypeInferred(dtype != declared);
        t.setHasMAC(hasMAC);
        t.setMultiplier(multiplier);
        t.setOffset(macOffset);
        t.setValuesX(valuesX);
        t.setValuesY(valuesY);
        t.setName("Table1D_0x" + Long.toHexString(headerAddr).toUpperCase());
        return t;
    }

    /**
     * Reads and validates a 1-D record's fixed fields and axis at {@code buf[off]}:
     * counts, type code, pointer placement, non-overlapping ranges and a valid X axis.
     */
    private static OneDHeaderPattern read1DHeaderPattern(byte[] buf, int off, ScanContext ctx) {
        if (off < 0 || off + 12 > buf.length) {
            return null;
        }

        int countX = readInt16BE(buf, off);
        int typeCode = buf[off + 2] & 0xFF;
        // High byte of the 2-byte little-endian type field must be zero
        if (!isValidCount(countX) || (buf[off + 3] & 0xFF) != 0) {
            return null;
        }
        if (!DensoTableType.fromCode(typeCode).isValid()) {
            return null;
        }

        long ptrX = readInt32BE(buf, off + 4);
        long ptrY = readInt32BE(buf, off + 8);
        long xLen = 4L * countX;
        // Data is sized as 1 byte per value for the overlap check: the type can be wrong.
        long yLen = countX;
        if (ptrX == ptrY || overlaps(ptrX, xLen, ptrY, yLen)) {
            return null;
        }
        if (!ctx.isReadable(ptrX, xLen) || !ctx.isReadable(ptrY, yLen)) {
            return null;
        }

        float[] valuesX = readFloatArrayFromMemory(ctx, ptrX, countX);
        if (valuesX == null || !isValidAxis(valuesX)) {
            return null;
        }

        return new OneDHeaderPattern(countX, typeCode, ptrX, ptrY);
    }

    /**
     * Reads and validates a 2-D record's fixed fields and axes at {@code buf[off]}:
     * counts, type code, pointer placement, non-overlapping ranges and valid X/Y axes.
     */
    private static TwoDHeaderPattern read2DHeaderPattern(byte[] buf, int off, ScanContext ctx) {
        if (off < 0 || off + 20 > buf.length) {
            return null;
        }

        int countX = readInt16BE(buf, off);
        int countY = readInt16BE(buf, off + 2);
        if (!isValidCount(countX) || !isValidCount(countY)) {
            return null;
        }
        int typeCode = buf[off + 16] & 0xFF;
        // Remaining 3 bytes of the little-endian type field must be zero
        if ((buf[off + 17] | buf[off + 18] | buf[off + 19]) != 0) {
            return null;
        }
        if (!DensoTableType.fromCode(typeCode).isValid()) {
            return null;
        }

        long ptrX = readInt32BE(buf, off + 4);
        long ptrY = readInt32BE(buf, off + 8);
        long ptrZ = readInt32BE(buf, off + 12);
        long xLen = 4L * countX;
        long yLen = 4L * countY;
        // Data is sized as 1 byte per value for the overlap check: the type can be wrong.
        long zLen = (long) countX * countY;
        if (ptrX == ptrY || ptrX == ptrZ || ptrY == ptrZ
                || overlaps(ptrX, xLen, ptrY, yLen)
                || overlaps(ptrX, xLen, ptrZ, zLen)
                || overlaps(ptrY, yLen, ptrZ, zLen)) {
            return null;
        }
        if (!ctx.isReadable(ptrX, xLen) || !ctx.isReadable(ptrY, yLen)
                || !ctx.isReadable(ptrZ, zLen)) {
            return null;
        }

        float[] valuesX = readFloatArrayFromMemory(ctx, ptrX, countX);
        if (valuesX == null || !isValidAxis(valuesX)) {
            return null;
        }
        float[] valuesY = readFloatArrayFromMemory(ctx, ptrY, countY);
        if (valuesY == null || !isValidAxis(valuesY)) {
            return null;
        }

        return new TwoDHeaderPattern(countX, countY, typeCode, ptrX, ptrY, ptrZ);
    }

    // =========================================================================
    // Data type inference (refines the type, never rejects a record)
    // =========================================================================

    private static DensoTableType inferPacked2DDataType(byte[] buf, int off,
            long blockStartOff, long ptrZ, int countX, int countY, ScanContext ctx) {
        long nextPtr = findNextLocalDataPointer(buf, off, blockStartOff, ptrZ, ctx);
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

    private static DensoTableType inferPacked1DDataType(byte[] buf, int off,
            long blockStartOff, int countX, long ptrX, long ptrY, ScanContext ctx) {
        long nextPtr = findNextLocalDataPointer(buf, off, blockStartOff, ptrY, ctx);
        long yStride = nextPtr == Long.MAX_VALUE ? Long.MAX_VALUE : nextPtr - ptrY;
        if (matchesCompactPayloadStride(yStride, countX)) {
            return DensoTableType.UINT8;
        }
        if (matchesCompactPayloadStride(yStride,
                countX * DensoTableType.UINT16.getValueSize())) {
            return DensoTableType.UINT16;
        }

        for (int delta : AMBIGUOUS_1D_NEIGHBOR_OFFSETS) {
            OneDHeaderPattern sibling = read1DHeaderPattern(buf, off + delta, ctx);
            if (sibling == null || sibling.count() != countX) {
                continue;
            }

            long xStride = Math.abs(sibling.ptrX() - ptrX);
            if (xStride != countX * 4L) {
                continue;
            }

            long siblingYStride = Math.abs(sibling.ptrY() - ptrY);
            DensoTableType siblingType = DensoTableType.fromCode(sibling.typeCode());

            if (siblingType.isValid()
                    && sibling.typeCode() != DensoTableType.FLOAT.getCode()
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

    /** True when the payload reads as floats that all pass {@link DensoTable#isValidFloat}. */
    private static boolean hasValidFloatPayload(ScanContext ctx, long ptr, int count) {
        float[] values = readFloatArrayFromMemory(ctx, ptr, count);
        if (values == null) {
            return false;
        }
        for (float v : values) {
            if (!DensoTable.isValidFloat(v)) return false;
        }
        return true;
    }

    private static DensoTableType inferAlignedIntegerDataType(ScanContext ctx, long ptrY,
            int countX) {
        if (hasAlignedIntegerSlots(ctx, ptrY, countX,
                DensoTableType.UINT16.getValueSize())) {
            return DensoTableType.UINT16;
        }
        if (hasAlignedIntegerSlots(ctx, ptrY, countX,
                DensoTableType.UINT8.getValueSize())) {
            return DensoTableType.UINT8;
        }
        return null;
    }

    private static boolean hasAlignedIntegerSlots(ScanContext ctx, long ptr, int count,
            int elemSize) {
        int payloadSize = count * elemSize;
        int alignedSize = alignTo4(payloadSize);
        if (alignedSize == payloadSize) {
            return false;
        }

        int slotsToCheck = 2;
        byte[] raw = readBytesFromMemory(ctx, ptr, alignedSize * slotsToCheck);
        if (raw == null) {
            raw = readBytesFromMemory(ctx, ptr, alignedSize);
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
            long blockStartOff, long ptr, ScanContext ctx) {
        long nextPtr = Long.MAX_VALUE;

        // Every 4-byte aligned descriptor within +/- LOCAL_DESCRIPTOR_SCAN_BYTES,
        // which already covers the immediate 1-D/2-D neighbor offsets.
        int scanStart = Math.max(0, off - LOCAL_DESCRIPTOR_SCAN_BYTES);
        int scanEnd = Math.min(buf.length - 12, off + LOCAL_DESCRIPTOR_SCAN_BYTES);
        for (int neighborOff = scanStart; neighborOff <= scanEnd; neighborOff += 4) {
            nextPtr = Math.min(nextPtr, nextHigherLocalDataPointer(buf, neighborOff, ptr, ctx));
        }

        return nextPtr;
    }

    private static long nextHigherLocalDataPointer(byte[] buf, int off, long ptr,
            ScanContext ctx) {
        long nextPtr = Long.MAX_VALUE;

        OneDHeaderPattern oneD = read1DHeaderPattern(buf, off, ctx);
        if (oneD != null) {
            if (oneD.ptrX() > ptr) {
                nextPtr = Math.min(nextPtr, oneD.ptrX());
            }
            if (oneD.ptrY() > ptr) {
                nextPtr = Math.min(nextPtr, oneD.ptrY());
            }
        }

        TwoDHeaderPattern twoD = read2DHeaderPattern(buf, off, ctx);
        if (twoD != null) {
            if (twoD.ptrX() > ptr) {
                nextPtr = Math.min(nextPtr, twoD.ptrX());
            }
            if (twoD.ptrY() > ptr) {
                nextPtr = Math.min(nextPtr, twoD.ptrY());
            }
            if (twoD.ptrZ() > ptr) {
                nextPtr = Math.min(nextPtr, twoD.ptrZ());
            }
        }

        return nextPtr;
    }

    // =========================================================================
    // Memory I/O
    // =========================================================================

    /**
     * Reads {@code count} big-endian floats starting at the given address.
     * Returns {@code null} on any access error.
     */
    private static float[] readFloatArrayFromMemory(ScanContext ctx, long ptr, int count) {
        byte[] raw = readBytesFromMemory(ctx, ptr, count * 4);
        if (raw == null) {
            return null;
        }
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            out[i] = readFloatBE(raw, i * 4);
        }
        return out;
    }

    private static byte[] readBytesFromMemory(ScanContext ctx, long ptr, int size) {
        if (!ctx.isReadable(ptr, size)) {
            return null;
        }
        try {
            byte[] raw = new byte[size];
            if (ctx.memory().getBytes(ctx.space().getAddress(ptr), raw) != size) {
                return null;
            }
            return raw;
        }
        catch (Exception e) {
            return null;
        }
    }

    /** Reads a 1-D data array of the given type (big-endian). */
    private static double[] readDataArray1D(ScanContext ctx, long ptr, int count,
            DensoTableType dtype) {
        int elemSize = dtype.getValueSize();
        byte[] raw = readBytesFromMemory(ctx, ptr, count * elemSize);
        if (raw == null) {
            return null;
        }
        double[] out = new double[count];
        for (int i = 0; i < count; i++) {
            out[i] = dtype.rawToDouble(readBigEndian(raw, i * elemSize, elemSize));
        }
        return out;
    }

    /** Reads a 2-D data matrix (row-major) of the given type (big-endian). */
    private static double[][] readDataMatrix(ScanContext ctx, long ptr, int countX,
            int countY, DensoTableType dtype) {
        int elemSize = dtype.getValueSize();
        byte[] raw = readBytesFromMemory(ctx, ptr, countX * countY * elemSize);
        if (raw == null) {
            return null;
        }
        double[][] out = new double[countY][countX];
        for (int y = 0; y < countY; y++) {
            for (int x = 0; x < countX; x++) {
                int byteOff = (y * countX + x) * elemSize;
                out[y][x] = dtype.rawToDouble(readBigEndian(raw, byteOff, elemSize));
            }
        }
        return out;
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
     * Returns true if every value is a valid calibration float and the array is
     * non-decreasing. Duplicate breakpoints do occur in real calibrations (e.g. a
     * MAF axis), so only descending axes are rejected.
     */
    static boolean isValidAxis(float[] arr) {
        for (int i = 0; i < arr.length; i++) {
            if (!DensoTable.isValidFloat(arr[i])) return false;
            if (i > 0 && arr[i] < arr[i - 1]) return false;
        }
        return true;
    }

    private static boolean isValidCount(int count) {
        return count >= MIN_COUNT && count <= MAX_COUNT;
    }

    /** True when {@code [a, a + aLen)} and {@code [b, b + bLen)} share any byte. */
    private static boolean overlaps(long a, long aLen, long b, long bLen) {
        return a < b + bLen && b < a + aLen;
    }
}
