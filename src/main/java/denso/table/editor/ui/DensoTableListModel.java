/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor.ui;

import java.util.ArrayList;
import java.util.List;

import denso.table.editor.model.*;
import docking.widgets.table.*;
import docking.widgets.table.threaded.ThreadedTableModelStub;
import ghidra.docking.settings.Settings;
import ghidra.framework.plugintool.ServiceProvider;
import ghidra.framework.plugintool.PluginTool;
import ghidra.util.datastruct.Accumulator;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

/**
 * {@link ThreadedTableModelStub} that backs the Denso table list view.
 *
 * <p>The model holds a snapshot of the last scan results.  Call
 * {@link #setTables(List)} (from the Swing thread) to update the list and
 * trigger a display refresh.
 */
public class DensoTableListModel extends ThreadedTableModelStub<DensoTable> {

    /** Column header name for the header address — used for click-detection. */
    public static final String HEADER_ADDRESS_COLUMN = "Header Address";

    private volatile List<DensoTable> snapshot = new ArrayList<>();

    public DensoTableListModel(PluginTool tool) {
        super("GhidraTables Model", tool);
    }

    /** Replaces the current table list and refreshes the view. */
    public void setTables(List<DensoTable> tables) {
        snapshot = new ArrayList<>(tables);
        reload();
    }

    // ── ThreadedTableModelStub ────────────────────────────────────────────────

    @Override
    protected void doLoad(Accumulator<DensoTable> accumulator, TaskMonitor monitor)
            throws CancelledException {
        List<DensoTable> copy = new ArrayList<>(snapshot);
        monitor.initialize(copy.size());
        for (DensoTable t : copy) {
            monitor.checkCancelled();
            accumulator.add(t);
            monitor.incrementProgress(1);
        }
    }

    @Override
    protected TableColumnDescriptor<DensoTable> createTableColumnDescriptor() {
        TableColumnDescriptor<DensoTable> d = new TableColumnDescriptor<>();
        d.addVisibleColumn(new NameColumn());
        d.addVisibleColumn(new CategoryColumn());
        d.addVisibleColumn(new TypeColumn());
        d.addVisibleColumn(new AddressColumn(), 0, true);  // sort by address by default
        d.addVisibleColumn(new DimensionsColumn());
        d.addVisibleColumn(new DataTypeColumn());
        d.addVisibleColumn(new MacColumn());
        return d;
    }

    // =========================================================================
    // Column definitions
    // =========================================================================

    private static class NameColumn
            extends AbstractDynamicTableColumn<DensoTable, String, Object> {
        @Override public String getColumnName() { return "Name"; }
        @Override public String getValue(DensoTable row, Settings s, Object data,
                ServiceProvider sp) {
            return row.getName();
        }
        @Override public int getColumnPreferredWidth() { return 200; }
    }

    private static class CategoryColumn
            extends AbstractDynamicTableColumn<DensoTable, String, Object> {
        @Override public String getColumnName() { return "Category"; }
        @Override public String getValue(DensoTable row, Settings s, Object data,
                ServiceProvider sp) {
            return row.getCategory();
        }
        @Override public int getColumnPreferredWidth() { return 120; }
    }

    private static class TypeColumn
            extends AbstractDynamicTableColumn<DensoTable, String, Object> {
        @Override public String getColumnName() { return "Type"; }
        @Override public String getValue(DensoTable row, Settings s, Object data,
                ServiceProvider sp) {
            return row.is2D() ? "2D" : "1D";
        }
        @Override public int getColumnPreferredWidth() { return 45; }
    }

    private static class AddressColumn
            extends AbstractDynamicTableColumn<DensoTable, String, Object> {
        @Override public String getColumnName() { return "Header Address"; }
        @Override public String getValue(DensoTable row, Settings s, Object data,
                ServiceProvider sp) {
            return row.getAddressHex();
        }
        @Override public int getColumnPreferredWidth() { return 130; }
    }

    private static class DimensionsColumn
            extends AbstractDynamicTableColumn<DensoTable, String, Object> {
        @Override public String getColumnName() { return "Size"; }
        @Override public String getValue(DensoTable row, Settings s, Object data,
                ServiceProvider sp) {
            return row.getDimensions();
        }
        @Override public int getColumnPreferredWidth() { return 70; }
    }

    private static class DataTypeColumn
            extends AbstractDynamicTableColumn<DensoTable, String, Object> {
        @Override public String getColumnName() { return "Data Type"; }
        @Override public String getValue(DensoTable row, Settings s, Object data,
                ServiceProvider sp) {
            return row.getDataType().getDisplayName();
        }
        @Override public int getColumnPreferredWidth() { return 80; }
    }

    private static class MacColumn
            extends AbstractDynamicTableColumn<DensoTable, String, Object> {
        @Override public String getColumnName() { return "MAC"; }
        @Override public String getValue(DensoTable row, Settings s, Object data,
                ServiceProvider sp) {
            return row.isHasMAC() ? row.getMacExpression() : "";
        }
        @Override public int getColumnPreferredWidth() { return 160; }
    }
}
