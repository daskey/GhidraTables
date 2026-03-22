/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor.model;

/**
 * A Denso ECU 2-D calibration table (two input axes, matrix output).
 * <p>
 * Binary header layout (big-endian unless noted):
 * <pre>
 *  Offset  Size  Description
 *  ------  ----  -------------------------------------------------------
 *   0      2     CountX (columns, big-endian int16)
 *   2      2     CountY (rows,    big-endian int16)
 *   4      4     Pointer to X-axis float array
 *   8      4     Pointer to Y-axis float array
 *  12      4     Pointer to Z-data array (row-major: Z[y][x])
 *  16      4     TableType code (little-endian – only first byte used)
 *  [20     4     Multiplier float (present when hasMAC == true)]
 *  [24     4     Offset   float (present when hasMAC == true)]
 * </pre>
 * Header size: 20 bytes (no MAC) or 28 bytes (with MAC).
 * <p>
 * Z-data is stored in row-major order: element {@code Z[y][x]} is at
 * byte offset {@code (y * countX + x) * dataType.getValueSize()} from
 * {@code ptrZ}.
 */
public class DensoTable2D extends DensoTable {

    /** ROM address of the Y-axis float array. */
    private long ptrY;

    /** ROM address of the Z-data array. */
    private long ptrZ;

    /** Number of Y-axis (row) entries. */
    private int countY;

    /** Y-axis float values read from the ROM. */
    private float[] valuesY = new float[0];

    /**
     * Raw Z-data matrix, indexed as {@code valuesZ[y][x]}.
     * The editor applies MAC conversion only when displaying or editing values.
     */
    private double[][] valuesZ = new double[0][0];

    // -------------------------------------------------------------------------
    // DensoTable contract
    // -------------------------------------------------------------------------

    @Override
    public boolean is2D() { return true; }

    @Override
    public String getDimensions() { return countX + "x" + countY; }

    @Override
    public DensoTable copy() {
        DensoTable2D copy = new DensoTable2D();
        copyCommonStateTo(copy);
        copy.ptrY = ptrY;
        copy.ptrZ = ptrZ;
        copy.countY = countY;
        copy.valuesY = java.util.Arrays.copyOf(valuesY, valuesY.length);
        copy.valuesZ = new double[valuesZ.length][];
        for (int i = 0; i < valuesZ.length; i++) {
            copy.valuesZ[i] = java.util.Arrays.copyOf(valuesZ[i], valuesZ[i].length);
        }
        return copy;
    }

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    public long getPtrY()               { return ptrY; }
    public void setPtrY(long v)        { ptrY = v; }

    public long getPtrZ()               { return ptrZ; }
    public void setPtrZ(long v)        { ptrZ = v; }

    public int getCountY()              { return countY; }
    public void setCountY(int v)       { countY = v; }

    public float[] getValuesY()        { return valuesY; }
    public void setValuesY(float[] v)  { valuesY = v != null ? v : new float[0]; }

    public double[][] getValuesZ()     { return valuesZ; }
    public void setValuesZ(double[][] v) { valuesZ = v != null ? v : new double[0][0]; }

    /** Convenience accessor for a single Z cell. */
    public double getZ(int row, int col) {
        if (row < 0 || row >= valuesZ.length) return 0;
        if (col < 0 || col >= valuesZ[row].length) return 0;
        return valuesZ[row][col];
    }

    /** Convenience setter for a single Z cell. */
    public void setZ(int row, int col, double value) {
        if (row >= 0 && row < valuesZ.length && col >= 0 && col < valuesZ[row].length) {
            valuesZ[row][col] = value;
        }
    }

    /**
     * Returns the global minimum physical value across all Z cells,
     * useful for heat-map normalisation.
     */
    public double getMinZ() {
        double min = Double.MAX_VALUE;
        for (double[] row : valuesZ) {
            for (double v : row) {
                if (v < min) min = v;
            }
        }
        return min == Double.MAX_VALUE ? 0 : min;
    }

    /**
     * Returns the global maximum physical value across all Z cells,
     * useful for heat-map normalisation.
     */
    public double getMaxZ() {
        double max = -Double.MAX_VALUE;
        for (double[] row : valuesZ) {
            for (double v : row) {
                if (v > max) max = v;
            }
        }
        return max == -Double.MAX_VALUE ? 0 : max;
    }
}
