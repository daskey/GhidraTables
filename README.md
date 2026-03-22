# GhidraTables

GhidraTables is a Ghidra extension for finding, reviewing, and editing Denso ECU calibration tables.

## Features

- Scan loaded ROMs for 1D and 2D Denso table headers
- Browse discovered tables in a filterable list
- Open a heat-map editor with multi-cell editing
- Write changes back to the loaded program
- Apply Ghidra data structures at detected table addresses

## Build

Set `GHIDRA_INSTALL_DIR` to a compatible Ghidra installation, then run the Gradle wrapper from the project root.

```powershell
$env:GHIDRA_INSTALL_DIR="C:\path\to\ghidra"
.\gradlew
```
