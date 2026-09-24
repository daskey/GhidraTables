/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;

import org.junit.*;

import denso.table.editor.model.*;
import ghidra.GhidraApplicationLayout;
import ghidra.framework.Application;
import ghidra.framework.HeadlessGhidraApplicationConfiguration;
import ghidra.program.database.ProgramDB;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.lang.Language;
import ghidra.program.model.lang.LanguageID;
import ghidra.program.util.DefaultLanguageService;
import ghidra.util.task.TaskMonitor;

/**
 * Scans synthetic big-endian ROM images loaded into real in-memory Ghidra
 * programs. Detection rules mirror ScoobyRom; see {@link DensoTableScanner}.
 */
public class DensoTableScannerTest {

    private static final int ROM_SIZE = 0x10000;
    private static final int TYPE_FLOAT = 0x00;
    private static final int TYPE_UINT8 = 0x04;
    private static final int TYPE_UINT16 = 0x08;

    /** Big-endian image loaded at address 0. */
    private final ByteBuffer rom = ByteBuffer.allocate(ROM_SIZE);

    @BeforeClass
    public static void initGhidra() throws Exception {
        if (!Application.isInitialized()) {
            String dir = System.getProperty("ghidra.install.dir");
            Assume.assumeNotNull(dir);
            Application.initializeApplication(new GhidraApplicationLayout(new File(dir)),
                    new HeadlessGhidraApplicationConfiguration());
        }
    }

    // ── ROM builders ─────────────────────────────────────────────────────────

    private void header1D(int at, int count, int type, int ptrX, int ptrY) {
        rom.putShort(at, (short) count);
        rom.put(at + 2, (byte) type);
        rom.put(at + 3, (byte) 0);
        rom.putInt(at + 4, ptrX);
        rom.putInt(at + 8, ptrY);
    }

    private void mac(int at, float multiplier, float offset) {
        rom.putFloat(at, multiplier);
        rom.putFloat(at + 4, offset);
    }

    private void header2D(int at, int countX, int countY, int ptrX, int ptrY, int ptrZ, int type) {
        rom.putShort(at, (short) countX);
        rom.putShort(at + 2, (short) countY);
        rom.putInt(at + 4, ptrX);
        rom.putInt(at + 8, ptrY);
        rom.putInt(at + 12, ptrZ);
        rom.put(at + 16, (byte) type);
    }

    private void floats(int at, float... values) {
        for (int i = 0; i < values.length; i++) {
            rom.putFloat(at + 4 * i, values[i]);
        }
    }

    private void shorts(int at, int... values) {
        for (int i = 0; i < values.length; i++) {
            rom.putShort(at + 2 * i, (short) values[i]);
        }
    }

    private void bytes(int at, int... values) {
        for (int i = 0; i < values.length; i++) {
            rom.put(at + i, (byte) values[i]);
        }
    }

    private static float[] ramp(int count, float start, float step) {
        float[] values = new float[count];
        for (int i = 0; i < count; i++) {
            values[i] = start + i * step;
        }
        return values;
    }

    private interface ProgramSetup {
        void apply(ProgramDB program, AddressSpace space) throws Exception;
    }

    private List<DensoTable> scan() throws Exception {
        return scan((program, space) -> {});
    }

    private List<DensoTable> scan(ProgramSetup extra) throws Exception {
        Language lang = DefaultLanguageService.getLanguageService()
                .getLanguage(new LanguageID("PowerPC:BE:32:default"));
        ProgramDB program = new ProgramDB("scan", lang, lang.getDefaultCompilerSpec(), this);
        try {
            AddressSpace space = program.getAddressFactory().getDefaultAddressSpace();
            int tx = program.startTransaction("rom");
            program.getMemory().createInitializedBlock("rom", space.getAddress(0),
                    new ByteArrayInputStream(rom.array()), ROM_SIZE, TaskMonitor.DUMMY, false);
            extra.apply(program, space);
            program.endTransaction(tx, true);
            List<DensoTable> tables = DensoTableScanner.scan(program, TaskMonitor.DUMMY);
            skipped = DensoTableScanner.describeSkippedBlocks(program);
            return tables;
        }
        finally {
            program.release(this);
        }
    }

    private List<String> skipped = List.of();

    private static List<Long> addresses(List<DensoTable> tables) {
        return tables.stream().map(DensoTable::getHeaderAddress).toList();
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    public void detectsBothRecordKinds() throws Exception {
        header1D(0x1000, 4, TYPE_FLOAT, 0x4000, 0x4010);
        floats(0x4000, 0, 10, 20, 30);
        floats(0x4010, 1.5f, 2.5f, 3.5f, 4.5f);

        header2D(0x1020, 3, 2, 0x4100, 0x4110, 0x4120, TYPE_UINT8);
        mac(0x1020 + 20, 0.5f, 0f);
        floats(0x4100, 1, 2, 3);
        floats(0x4110, 10, 20);
        bytes(0x4120, 1, 2, 3, 4, 5, 6);

        List<DensoTable> tables = scan();
        assertEquals(List.of(0x1000L, 0x1020L), addresses(tables));

        DensoTable1D t1 = (DensoTable1D) tables.get(0);
        assertEquals(DensoTableType.FLOAT, t1.getDataType());
        assertFalse(t1.isTypeInferred());
        assertFalse(t1.isHasMAC());
        assertArrayEquals(new double[] { 1.5, 2.5, 3.5, 4.5 }, t1.getValuesY(), 0);

        DensoTable2D t2 = (DensoTable2D) tables.get(1);
        assertEquals("3x2", t2.getDimensions());
        assertEquals(DensoTableType.UINT8, t2.getDataType());
        assertTrue(t2.isHasMAC());
        assertEquals(0.5f, t2.getMultiplier(), 0);
        assertEquals(6.0, t2.getZ(1, 2), 0);
    }

    @Test
    public void floatTableWithIntegerPayloadIsKeptAsUInt16() throws Exception {
        // Declared float, no MAC, but the payload is 16-bit integers. The old
        // scanner rejected these outright; ScoobyRom reads them as UInt16.
        header1D(0x1000, 4, TYPE_FLOAT, 0x4000, 0x4800);
        floats(0x4000, 0, 10, 20, 30);
        shorts(0x4800, 400, 450, 500, 550);

        List<DensoTable> tables = scan();
        assertEquals(List.of(0x1000L), addresses(tables));
        DensoTable1D t = (DensoTable1D) tables.get(0);
        assertEquals(DensoTableType.UINT16, t.getDataType());
        assertTrue(t.isTypeInferred());
        assertArrayEquals(new double[] { 400, 450, 500, 550 }, t.getValuesY(), 0);
    }

    @Test
    public void macFloatTableIsKeptWhateverItsPayload() throws Exception {
        header1D(0x1000, 2, TYPE_FLOAT, 0x4000, 0x4800);
        mac(0x1000 + 12, 0.5f, 0f);
        floats(0x4000, 1, 2);
        floats(0x4800, Float.NaN, 1e30f);

        List<DensoTable> tables = scan();
        assertEquals(List.of(0x1000L), addresses(tables));
        assertEquals(DensoTableType.FLOAT, tables.get(0).getDataType());
        assertTrue(tables.get(0).isHasMAC());
    }

    @Test
    public void acceptsDataRightNextToTheHeader() throws Exception {
        // Compact layout: axis and data within 0x100 bytes of the record.
        header1D(0x3000, 4, TYPE_UINT8, 0x3010, 0x3020);
        floats(0x3010, 0, 10, 20, 30);
        bytes(0x3020, 1, 2, 3, 4);

        assertEquals(List.of(0x3000L), addresses(scan()));
    }

    @Test
    public void axisCountsMustBeTwoTo255() throws Exception {
        header1D(0x1000, 1, TYPE_UINT8, 0x4000, 0x4010);
        floats(0x4000, 5);
        bytes(0x4010, 7);

        header1D(0x1100, 2, TYPE_UINT8, 0x4100, 0x4110);
        floats(0x4100, 5, 6);
        bytes(0x4110, 7, 8);

        header1D(0x1200, 255, TYPE_UINT8, 0x5000, 0x5800);
        floats(0x5000, ramp(255, 0, 1));
        bytes(0x5800, new int[255]);

        header1D(0x1300, 256, TYPE_UINT8, 0x6000, 0x6800);
        floats(0x6000, ramp(256, 0, 1));

        assertEquals(List.of(0x1100L, 0x1200L), addresses(scan()));
    }

    @Test
    public void pointersIntoTheFirst8KiBAreRejected() throws Exception {
        header1D(0x1000, 2, TYPE_UINT8, 0x4000, 0x1800);
        floats(0x4000, 1, 2);
        bytes(0x1800, 3, 4);

        assertTrue(scan().isEmpty());
    }

    @Test
    public void sharedOrOverlappingPointersAreRejected() throws Exception {
        floats(0x4000, 1, 2, 3, 4);
        // Y data starts inside the X axis
        header1D(0x1000, 4, TYPE_UINT8, 0x4000, 0x4008);
        // 2-D record whose X and Y axes are the same array
        header2D(0x1100, 4, 4, 0x4000, 0x4000, 0x4800, TYPE_UINT8);

        assertTrue(scan().isEmpty());
    }

    @Test
    public void axisValuesFollowScoobyRomRules() throws Exception {
        int[] headers = { 0x1000, 0x1100, 0x1200, 0x1300, 0x1400 };
        float[][] axes = {
            { 1e11f, 2e11f },   // large but valid
            { 1f, 1f, 2f },     // duplicate breakpoint is allowed
            { 0f, 1e13f },      // too large
            { 1e-13f, 1f },     // too small to be real
            { 2f, 1f },         // descending
        };
        for (int i = 0; i < headers.length; i++) {
            int x = 0x4000 + i * 0x100;
            header1D(headers[i], axes[i].length, TYPE_UINT8, x, x + 0x40);
            floats(x, axes[i]);
        }

        assertEquals(List.of(0x1000L, 0x1100L), addresses(scan()));
    }

    @Test
    public void macUsesScoobyRomFloatRange() throws Exception {
        header1D(0x1000, 2, TYPE_UINT16, 0x4000, 0x4010);
        mac(0x1000 + 12, 1e-9f, 0f);
        floats(0x4000, 1, 2);
        shorts(0x4010, 3, 4);

        List<DensoTable> tables = scan();
        assertEquals(1, tables.size());
        assertTrue(tables.get(0).isHasMAC());
        assertEquals(1e-9f, tables.get(0).getMultiplier(), 0);
    }

    @Test
    public void overlayBlocksAreSkippedAndReported() throws Exception {
        header1D(0x1000, 2, TYPE_UINT8, 0x4000, 0x4010);
        floats(0x4000, 1, 2);
        bytes(0x4010, 3, 4);

        byte[] copy = new byte[0x100];
        rom.get(0x1000, copy);
        List<DensoTable> tables = scan((program, space) ->
                program.getMemory().createInitializedBlock("cal", space.getAddress(0x1000),
                        new ByteArrayInputStream(copy), copy.length, TaskMonitor.DUMMY, true));

        assertEquals(List.of(0x1000L), addresses(tables));
        assertEquals(1, skipped.size());
        assertTrue(skipped.get(0), skipped.get(0).startsWith("cal (overlay space"));
    }
}
