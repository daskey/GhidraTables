/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor;

import denso.table.editor.ui.DensoTableListProvider;
import denso.table.editor.ui.GhidraTablesEditorFrame;
import docking.widgets.OptionDialog;
import ghidra.app.plugin.PluginCategoryNames;
import ghidra.app.plugin.ProgramPlugin;
import ghidra.framework.model.DomainObject;
import ghidra.framework.plugintool.PluginTool;
import ghidra.framework.plugintool.PluginInfo;
import ghidra.framework.plugintool.util.PluginStatus;
import ghidra.program.model.listing.Program;

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
 *   <li>Edit values, then click <b>Save</b> to write back to the program.</li>
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
    protected void programClosed(Program program) {
        // Editors keep a reference to their program; once it is closed a save
        // would fail, so close them along with it.
        listProvider.closeEditors(program);
    }

    @Override
    protected boolean canCloseDomainObject(DomainObject dObj) {
        if (dObj instanceof Program program) {
            return confirmDiscardUnsavedEdits(listProvider.countUnsavedEditors(program));
        }
        return true;
    }

    @Override
    protected boolean canClose() {
        return confirmDiscardUnsavedEdits(listProvider.countUnsavedEditors(null));
    }

    @Override
    protected void dispose() {
        listProvider.dispose();
    }

    private boolean confirmDiscardUnsavedEdits(int unsavedEditors) {
        if (unsavedEditors == 0) {
            return true;
        }
        int choice = OptionDialog.showYesNoDialog(tool.getToolFrame(), "Unsaved Table Edits",
                unsavedEditors + " GhidraTables editor" + (unsavedEditors == 1 ? " has" : "s have") +
                " unsaved changes that will be lost.\nClose anyway?");
        return choice == OptionDialog.YES_OPTION;
    }
}
