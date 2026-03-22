/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor;

import denso.table.editor.ui.DensoTableListProvider;
import denso.table.editor.ui.GhidraTablesEditorFrame;
import ghidra.app.plugin.PluginCategoryNames;
import ghidra.app.plugin.ProgramPlugin;
import ghidra.framework.plugintool.PluginTool;
import ghidra.framework.plugintool.PluginInfo;
import ghidra.framework.plugintool.util.PluginStatus;
import ghidra.program.model.listing.Program;
import ghidra.program.util.ProgramLocation;
import ghidra.program.util.ProgramSelection;

/**
 * Main entry point for the GhidraTables extension.
 *
 * <p>The plugin creates a dockable {@link DensoTableListProvider} that lets
 * the analyst scan the current ROM for Denso 1-D and 2-D calibration table
 * headers.  Double-clicking a row in the list opens a pop-out
 * {@link GhidraTablesEditorFrame} with a heat-map grid
 * and multi-cell editing support.
 *
 * <h3>Quick start</h3>
 * <ol>
 *   <li>Load a Denso ECU binary in Ghidra (File → Import).</li>
 *   <li>Open the plugin from <b>Window → GhidraTables</b>.</li>
 *   <li>Click <b>Scan ROM</b> (↻ toolbar icon).</li>
 *   <li>Double-click any table to open the editor.</li>
 *   <li>Edit values, then click <b>Apply Changes</b> to write back to the ROM.</li>
 * </ol>
 */
//@formatter:off
@PluginInfo(
    status      = PluginStatus.STABLE,
    packageName = ghidra.app.ExamplesPluginPackage.NAME,
    category    = PluginCategoryNames.ANALYSIS,
    shortDescription = "GhidraTables",
    description = "GhidraTables scans Denso ECU ROM images for 1-D and 2-D " +
                  "calibration lookup tables, displays them in a heat-map grid, " +
                  "and supports multi-cell editing with write-back to the loaded program."
)
//@formatter:on
public class GhidraTablesPlugin extends ProgramPlugin {

    private DensoTableListProvider listProvider;

    public GhidraTablesPlugin(PluginTool tool) {
        super(tool);

        listProvider = new DensoTableListProvider(this);
        listProvider.addToTool();
    }

    // ── ProgramPlugin lifecycle ───────────────────────────────────────────────

    @Override
    protected void programActivated(Program program) {
        listProvider.programChanged(program);
    }

    @Override
    protected void programDeactivated(Program program) {
        listProvider.programChanged(null);
    }

    @Override
    protected void locationChanged(ProgramLocation loc) { /* not used */ }

    @Override
    protected void selectionChanged(ProgramSelection sel) { /* not used */ }

    @Override
    protected void dispose() {
        listProvider.dispose();
    }

    // ── Package-visible helpers ───────────────────────────────────────────────

    /** Exposes the currently active program to the list provider and editor. */
    public Program getCurrentProgram() {
        return currentProgram;
    }
}
