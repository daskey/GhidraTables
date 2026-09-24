# GhidraTables

GhidraTables is a Ghidra extension for finding, reviewing, and editing Denso ECU calibration tables.

## Warning

This tool can help you move quickly, but it is not magic and it is not authoritative.

- Do not blindly trust autodetected tables, inferred data types, MAC values, or applied structures.
- Do not blindly trust AI-generated code, AI-generated calibration changes, or "looks about right" table edits.
- Verify every important table in disassembly, data layout, and live behavior before you flash anything.
- If you use this to modify an ECU and do not validate the result properly, you can absolutely damage a calibration, a vehicle, or both.

Use it as an accelerator for analysis, not as a substitute for understanding the ROM.

## What It Does

- Scan loaded ROMs for 1D and 2D Denso table headers
- Browse discovered tables in a filterable list
- Open a heat-map editor with multi-cell editing
- Write changes back to the loaded program
- Apply Ghidra data structures at detected table addresses

## Build

Use JDK 21 and set `GHIDRA_INSTALL_DIR` to your Ghidra installation, then run the Gradle wrapper from the project root. The build and tests target Ghidra 11.4.2 with Gradle 8.14.5. The `dev` branch includes a read-only 3D view using jzy3d.

```powershell
$env:GHIDRA_INSTALL_DIR="C:\path\to\ghidra"
.\gradlew buildExtension
```

The packaged extension zip is written to `dist/`.

## Basic Workflow

1. Open the target ROM/program in Ghidra.
2. Open the `GhidraTables` provider and click the scan action.
3. Filter and review detected tables in the list.
4. Double-click a row to open the editor, or double-click the header address to navigate in the listing.
5. After validating the table, save changes back to the loaded program.

## Table List

- `Enter` opens the selected table in the editor.
- Double-clicking the `Header Address` column navigates to that address in the listing.
- Double-clicking any other column opens the table editor.
- The list is filterable through the built-in filter bar at the bottom.

### Bulk Structure Application

If you want Ghidra to lay out a whole calibration block properly:

1. Scan the ROM.
2. Select the tables you want to mark up.
3. Use `Ctrl+A` in the table list if you want to select everything currently shown.
4. Right-click and choose `Apply Structure`.

That will apply the detected table header plus the relevant axis/data structures so the listing becomes much easier to read and navigate.

## Table Editor

The editor displays physical values, but stores and writes raw table data.

- Table edits modify raw payload data.
- MAC edits modify the MAC fields in the table header.
- Changing MAC values does not rewrite the raw table payload.

### Selection And Editing

- Click or drag to select cells.
- Multi-cell edits apply across the current selection.
- `Undo` keeps the last 50 operations, including typing, paste, curve tools, and MAC changes.
- `Ctrl+Z` undoes; `Ctrl+Y` or `Ctrl+Shift+Z` redoes. `Ctrl+S` saves.
- `Ctrl+C` and `Ctrl+V` copy and paste full-precision values. Paste a rectangle at one selected anchor cell, or select a region of the same shape. A single copied value fills the selection.
- Invalid, out-of-range, or read-only paste destinations reject the whole paste. No partial edits are kept.
- Values are rounded to the storage type before display and save. Opening and closing an unchanged cell leaves it alone; deliberately retyping a value applies it across the selection.
- `Set Value`, `Fill Right`, and `Fill Down` are available from the context menu.

### Curve Tools

`Interpolate` and `Smooth` operate on the current selection.

- For 1D tables they run across the X axis.
- For 2D tables they run across rows or columns depending on the axis setting.
- In `Auto`, the editor will infer a sensible direction from the selection shape.
- Drag-select multiple cells first, then run `Interpolate` or `Smooth`.

### Mouse Wheel Adjustment

Hover over selected editable cells to adjust them with the mouse wheel. Elsewhere, the wheel scrolls the grid normally.

- Mouse wheel: `+/- 1.0`
- `Ctrl` + mouse wheel: `+/- 0.1`
- `Shift` + mouse wheel: `+/- 10.0`
- `Ctrl+Shift` + mouse wheel: `+/- 0.01`

Consecutive ticks on the same selection and step within 500 ms share one undo entry. Adjustments saturate at the storage type limits; steps smaller than one representable storage unit may round to no change.

### Other Editor Actions

- `Save` writes pending changes back to the loaded Ghidra program.
- `Revert` reloads the table from ROM and clears edit history only after a successful read. A failed or incomplete reload retains the editor values.
- `Actions` exposes `Export CSV` and `Apply Structure`. CSV includes axis breakpoints and full-precision physical values. Structure application rolls back the whole batch if any selected range fails.
- `Inspector` toggles the right-hand info pane.
- `3D` opens a read-only surface view, initialized when first requested.
- Opening the same table again reuses its editor. Closing a program or the tool warns about unsaved editor values and closes its editor windows.

## Detection Notes

Detection is pattern-based and intentionally permissive enough to catch compact Denso table layouts that are easy to miss in dense calibration regions. That also means you should still validate candidates before editing them.

The scanner reads initialized memory in the default address space in bounded chunks. Overlay blocks are excluded because the table model stores addresses in the default space. Header candidates must be aligned to an absolute four-byte address and all axis and data reads must complete. Compact payload types and optional MAC fields remain heuristic detections that need verification.

## Tests

With `GHIDRA_INSTALL_DIR` configured:

```sh
./gradlew test buildExtension
```

The default test run is headless. It covers type conversion, atomic reloads and edits, undo/redo, scanner boundaries and multi-cell editor behavior. Window tests are skipped without a display. To run them on Linux:

```sh
xvfb-run -a ./gradlew -Djava.awt.headless=false test buildExtension
```

The GitHub Actions workflow runs the window tests with Xvfb as well as packaging the extension.

## Short Version

- Scan the ROM.
- Validate the table.
- `Ctrl+A` in the list and `Apply Structure` if you want the calibration region marked up.
- Drag-select cells in the editor to interpolate, smooth, or wheel-adjust values.
- Verify everything before you flash anything.
