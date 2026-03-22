/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor.model;

/**
 * A Denso ECU 1-D calibration table (single input axis, curve output).
 * <p>
 * Binary header layout (big-endian unless noted):
 * <pre>
 *  Offset  Size  Description
 *  ------  ----  -------------------------------------------------------
 *   0      2     CountX (number of entries, big-endian int16)
 *   2      2     TableType code (little-endian – only first byte used)
 *   4      4     Pointer to X-axis float array
 *   8      4     Pointer to Y-data array
 *  [12     4     Multiplier float (present when hasMAC == true)]
 *  [16     4     Offset   float (present when hasMAC == true)]
 * </pre>
 * Header size: 12 bytes (no MAC) or 20 bytes (with MAC).
 */
public class DensoTable1D extends DensoTable {

    /** ROM address of the Y-data (value) array. */
    private long ptrY;

    /**
     * Physical Y-axis data values.  Length equals {@code countX}.
     * Values are stored as physical units (MAC applied if present).
     */
    private double[] valuesY = new double[0];

    // -------------------------------------------------------------------------
    // DensoTable contract
    // -------------------------------------------------------------------------

    @Override
    public boolean is2D() { return false; }

    @Override
    public String getDimensions() { return countX + "x1"; }

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    public long getPtrY()              { return ptrY; }
    public void setPtrY(long v)       { ptrY = v; }

    public double[] getValuesY()      { return valuesY; }
    public void setValuesY(double[] v) { valuesY = v != null ? v : new double[0]; }

    /**
     * Returns the number of data pairs in this table
     * (should equal {@link #getCountX()}).
     */
    public int getDataLength()        { return valuesY.length; }
}
