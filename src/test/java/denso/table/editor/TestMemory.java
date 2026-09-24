package denso.table.editor;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.*;

/** In-memory API fixture that deliberately returns short reads at a block boundary. */
final class TestMemory {
    final AddressSpace space = new GenericAddressSpace("ram", 32, AddressSpace.TYPE_RAM, 0);
    final List<Region> regions = new ArrayList<>();
    long failReadAt = -1;
    long failWriteAt = -1;
    private List<byte[]> transaction;
    final Memory memory = proxy(Memory.class, (method, args) -> switch (method) {
        case "getBlocks" -> regions.stream().map(r -> r.block).toArray(MemoryBlock[]::new);
        case "getBlock" -> {
            Region r = region((Address) args[0]);
            yield r == null ? null : r.block;
        }
        case "getBytes" -> {
            Address address = (Address) args[0];
            if (address.getOffset() == failReadAt) throw new MemoryAccessException("Simulated read failure");
            Region r = region(address);
            if (r == null) throw new MemoryAccessException("Unmapped");
            byte[] out = (byte[]) args[1];
            int start = (int) (address.getOffset() - r.start);
            int length = Math.min(out.length, r.bytes.length - start);
            System.arraycopy(r.bytes, start, out, 0, length);
            yield length;
        }
        case "setBytes" -> {
            Address address = (Address) args[0];
            if (address.getOffset() == failWriteAt) throw new MemoryAccessException("Simulated write failure");
            Region r = region(address);
            byte[] bytes = (byte[]) args[1];
            if (r == null || address.getOffset() - r.start + bytes.length > r.bytes.length)
                throw new MemoryAccessException("Write exceeds block");
            System.arraycopy(bytes, 0, r.bytes, (int) (address.getOffset() - r.start), bytes.length);
            yield null;
        }
        default -> throw new UnsupportedOperationException(method);
    });
    final Program program = proxy(Program.class, (method, args) -> switch (method) {
        case "getMemory" -> memory;
        case "getAddressFactory" -> new DefaultAddressFactory(new AddressSpace[] {space}, space);
        case "isClosed" -> false;
        case "startTransaction" -> {
            transaction = regions.stream().map(r -> r.bytes.clone()).toList();
            yield 1;
        }
        case "endTransaction" -> {
            if (!(boolean) args[1]) {
                for (int i = 0; i < regions.size(); i++) {
                    System.arraycopy(transaction.get(i), 0, regions.get(i).bytes, 0, transaction.get(i).length);
                }
            }
            transaction = null;
            yield null;
        }
        default -> throw new UnsupportedOperationException(method);
    });

    Region add(long start, int size) { return add(space, start, size); }
    Region add(AddressSpace addressSpace, long start, int size) {
        Region region = new Region(addressSpace, start, size);
        regions.add(region);
        return region;
    }

    private Region region(Address address) {
        return regions.stream().filter(r -> r.addressSpace.equals(address.getAddressSpace())
                && address.getOffset() >= r.start
                && address.getOffset() < r.start + r.bytes.length).findFirst().orElse(null);
    }

    final class Region {
        final AddressSpace addressSpace;
        final long start;
        final byte[] bytes;
        final MemoryBlock block;
        Region(AddressSpace addressSpace, long start, int size) {
            this.addressSpace = addressSpace;
            this.start = start;
            bytes = new byte[size];
            block = proxy(MemoryBlock.class, (method, args) -> switch (method) {
                case "isInitialized" -> true;
                case "isExternalBlock" -> false;
                case "getSize" -> (long) size;
                case "getStart" -> addressSpace.getAddress(start);
                default -> throw new UnsupportedOperationException(method);
            });
        }
        ByteBuffer at(long address) { return ByteBuffer.wrap(bytes, (int) (address - start), bytes.length - (int) (address - start)); }
    }

    @FunctionalInterface private interface Handler {
        Object invoke(String method, Object[] args) throws Exception;
    }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    if (method.getName().equals("toString")) return "Test " + type.getSimpleName();
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == args[0];
                    return handler.invoke(method.getName(), args);
                });
    }
}
