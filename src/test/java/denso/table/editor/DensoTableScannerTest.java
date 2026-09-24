package denso.table.editor;

import java.util.List;
import denso.table.editor.model.*;
import ghidra.program.model.address.*;
import ghidra.util.task.TaskMonitorAdapter;
import ghidra.util.exception.CancelledException;
import org.junit.Test;
import static org.junit.Assert.*;

public class DensoTableScannerTest {
    private void header(TestMemory.Region block, long address) {
        block.at(address).putShort((short) 2).put((byte) 4).put((byte) 0)
                .putInt(0x1800).putInt(0x1900);
        block.at(0x1800).putFloat(1000).putFloat(2000);
        block.at(0x1900).put((byte) 10).put((byte) 20);
    }

    @Test public void scansOnlyTheDefaultAddressSpace() throws Exception {
        TestMemory fixture = new TestMemory();
        header(fixture.add(0x1000, 0x1000), 0x1100);
        AddressSpace other = new GenericAddressSpace("overlay", 32, AddressSpace.TYPE_RAM, 1);
        header(fixture.add(other, 0x1000, 0x1000), 0x1100);
        List<DensoTable> tables = DensoTableScanner.scan(fixture.program, new TaskMonitorAdapter());
        assertEquals(1, tables.size());
        assertEquals(0x1100, tables.get(0).getHeaderAddress());
    }

    @Test public void alignsAddressesRatherThanBlockOffsets() throws Exception {
        TestMemory fixture = new TestMemory();
        header(fixture.add(0x1001, 0x1000), 0x1100);
        List<DensoTable> tables = DensoTableScanner.scan(fixture.program, new TaskMonitorAdapter());
        assertEquals(1, tables.size());
        assertEquals(0x1100, tables.get(0).getHeaderAddress());
    }

    @Test public void headerAcrossChunkBoundaryIsDetectedOnce() throws Exception {
        TestMemory fixture = new TestMemory();
        long address = 0x1000 + 1024 * 1024 - 4;
        header(fixture.add(0x1000, 1024 * 1024 + 0x1000), address);
        List<DensoTable> tables = DensoTableScanner.scan(fixture.program, new TaskMonitorAdapter());
        assertEquals(1, tables.size());
        assertEquals(address, tables.get(0).getHeaderAddress());
    }

    @Test public void shortPayloadDoesNotCreatePhantomZeroCells() throws Exception {
        TestMemory fixture = new TestMemory();
        TestMemory.Region block = fixture.add(0x1000, 0x901);
        block.at(0x1100).putShort((short) 2).put((byte) 4).put((byte) 0)
                .putInt(0x1800).putInt(0x1900);
        block.at(0x1800).putFloat(1).putFloat(2);
        assertTrue(DensoTableScanner.scan(fixture.program, new TaskMonitorAdapter()).isEmpty());
    }

    @Test public void cancellationStopsAnInBlockScan() {
        TestMemory fixture = new TestMemory();
        fixture.add(0x1000, 0x10000);
        TaskMonitorAdapter monitor = new TaskMonitorAdapter(true) {
            int checks;
            @Override public void checkCancelled() throws CancelledException {
                if (++checks > 50) throw new CancelledException();
            }
        };
        assertThrows(CancelledException.class, () -> DensoTableScanner.scan(fixture.program, monitor));
    }
}
