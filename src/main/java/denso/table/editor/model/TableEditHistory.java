package denso.table.editor.model;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Objects;

/** Atomic edit batches and bounded undo history, independent of Swing and Ghidra. */
public final class TableEditHistory {
    private static final int LIMIT = 50;
    private static final long MERGE_NANOS = 500_000_000L;
    private record Entry(String description, DensoTable values) {}

    private final DensoTable table;
    private final Deque<Entry> undo = new ArrayDeque<>();
    private final Deque<Entry> redo = new ArrayDeque<>();
    private DensoTable saved;
    private Object mergeKey;
    private long lastEditTime;

    public TableEditHistory(DensoTable table) {
        this.table = table;
        markSaved();
    }

    public boolean apply(String description, Runnable operation) {
        return apply(description, null, operation);
    }

    /** A non-null key groups consecutive wheel ticks on the same selection and step. */
    public boolean apply(String description, Object key, Runnable operation) {
        DensoTable before = table.copy();
        try {
            operation.run();
        } catch (RuntimeException ex) {
            restore(table, before);
            breakMerge();
            throw ex;
        }
        if (sameData(table, before) && sameMac(table, before)) {
            if (key == null) breakMerge();
            return false;
        }
        long now = System.nanoTime();
        if (key == null || !Objects.equals(key, mergeKey)
                || now - lastEditTime > MERGE_NANOS || undo.isEmpty()) {
            undo.push(new Entry(description, before));
            if (undo.size() > LIMIT) undo.removeLast();
        }
        redo.clear();
        mergeKey = key;
        lastEditTime = now;
        return true;
    }

    public String undo() { return move(undo, redo); }
    public String redo() { return move(redo, undo); }

    private String move(Deque<Entry> from, Deque<Entry> to) {
        breakMerge();
        if (from.isEmpty()) return null;
        Entry entry = from.pop();
        to.push(new Entry(entry.description(), table.copy()));
        restore(table, entry.values());
        return entry.description();
    }

    public boolean isDataDirty() { return !sameData(table, saved); }
    public boolean isMacDirty() { return !sameMac(table, saved); }

    public void markSaved() {
        saved = table.copy();
        breakMerge();
    }

    public void reset() {
        undo.clear();
        redo.clear();
        markSaved();
    }

    public void breakMerge() { mergeKey = null; }

    private static boolean sameData(DensoTable a, DensoTable b) {
        if (a.is2D()) {
            return Arrays.deepEquals(((DensoTable2D) a).getValuesZ(),
                    ((DensoTable2D) b).getValuesZ());
        }
        return Arrays.equals(((DensoTable1D) a).getValuesY(),
                ((DensoTable1D) b).getValuesY());
    }

    private static boolean sameMac(DensoTable a, DensoTable b) {
        return Float.compare(a.getMultiplier(), b.getMultiplier()) == 0
                && Float.compare(a.getOffset(), b.getOffset()) == 0;
    }

    /** Copies editable state only; dimensions, addresses and axes are unchanged. */
    private static void restore(DensoTable target, DensoTable source) {
        target.setMultiplier(source.getMultiplier());
        target.setOffset(source.getOffset());
        if (target.is2D()) {
            double[][] values = ((DensoTable2D) source).getValuesZ();
            double[][] copy = new double[values.length][];
            for (int i = 0; i < values.length; i++) copy[i] = values[i].clone();
            ((DensoTable2D) target).setValuesZ(copy);
        } else {
            ((DensoTable1D) target).setValuesY(((DensoTable1D) source).getValuesY().clone());
        }
    }
}
