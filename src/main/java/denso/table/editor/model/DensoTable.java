/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor.model;

import java.util.Arrays;

/**
 * Abstract representation of a Denso ECU calibration lookup table.
 * <p>
 * Both 1-D and 2-D tables share a header that contains:
 * <ul>
 *   <li>CountX – number of X-axis samples</li>
 *   <li>A data-type identifier</li>
 *   <li>Pointer(s) to axis and data arrays</li>
 *   <li>An optional Multiplier-Additive-Correction (MAC) pair</li>
 * </ul>
 * The MAC pair, when present, converts raw stored values to physical units:
 * {@code physical = raw * multiplier + offset}.
 */
public abstract class DensoTable {

    /**
     * Smallest non-zero magnitude of a valid calibration float. Values like
     * x.xxE-40 come from misread bytes, not calibration data (ScoobyRom's FloatMin).
     */
    public static final float MIN_FLOAT_MAGNITUDE = 1e-12f;
    /** Largest magnitude of a valid calibration float (ScoobyRom's FloatMax). */
    public static final float MAX_FLOAT_MAGNITUDE = 1e12f;

    /** ROM address of the table header (in Ghidra address space). */
    protected long headerAddress;

    /** ROM address of the X-axis data array. */
    protected long ptrX;

    /** Number of X-axis entries. */
    protected int countX;

    /** Storage type for the data (not the axis arrays, which are always float). */
    protected DensoTableType dataType = DensoTableType.UNDEFINED;

    /**
     * True when {@link #dataType} was inferred by the scanner instead of taken
     * from the header's type code, so it should be verified.
     */
    protected boolean typeInferred;

    /** Whether a MAC (scale/offset) pair is present in the header. */
    protected boolean hasMAC;

    /**
     * Scale factor read from the table header when MAC is present.
     * Defaults to the identity transform for tables that do not carry MAC data.
     */
    protected float multiplier = 1.0f;

    /**
     * Additive offset read from the table header when MAC is present.
     * Defaults to the identity transform for tables that do not carry MAC data.
     */
    protected float offset = 0.0f;

    /** X-axis float values read from the ROM. */
    protected float[] valuesX = new float[0];

    /** User-assigned or auto-generated name shown in the table list. */
    protected String name = "";

    /** Optional category label from an XML definition file. */
    protected String category = "";

    // -------------------------------------------------------------------------
    // Abstract interface
    // -------------------------------------------------------------------------

    /** Returns {@code true} for 2-D (matrix) tables, {@code false} for 1-D (curve). */
    public abstract boolean is2D();

    /** Returns a human-readable dimension string, e.g. {@code "16x8"} or {@code "12x1"}. */
    public abstract String getDimensions();

    /** Returns a detached copy suitable for editing without mutating the original scan result. */
    public abstract DensoTable copy();

    // -------------------------------------------------------------------------
    // MAC helpers
    // -------------------------------------------------------------------------

    /**
     * Converts a raw (stored) value to its physical representation.
     * If no MAC is present the raw value is returned unchanged.
     */
    public double toPhysical(double raw) {
        return hasMAC ? raw * multiplier + offset : raw;
    }

    /**
     * Converts a physical value back to the raw representation for writing.
     * If no MAC is present the physical value is returned unchanged.
     */
    public double toRaw(double physical) {
        if (hasMAC && multiplier != 0f) {
            return (physical - offset) / multiplier;
        }
        return physical;
    }

    /** Returns the MAC expression string, e.g. {@code "x * 0.01 - 40.0"}, or empty string. */
    public String getMacExpression() {
        if (!hasMAC) return "";
        String op = offset >= 0 ? "+" : "-";
        return String.format("x * %.6g %s %.6g", multiplier, op, Math.abs(offset));
    }

    /**
     * Returns true for 0 and for values whose magnitude is within
     * [{@link #MIN_FLOAT_MAGNITUDE}, {@link #MAX_FLOAT_MAGNITUDE}]. NaN and
     * infinities are invalid. This is the scanner's check for axis and MAC floats.
     */
    public static boolean isValidFloat(float value) {
        if (Float.isNaN(value)) return false;
        if (value == 0f) return true;
        float magnitude = Math.abs(value);
        return magnitude >= MIN_FLOAT_MAGNITUDE && magnitude <= MAX_FLOAT_MAGNITUDE;
    }

    /**
     * Validates MAC parameters against the same rules the scanner uses to decide
     * that a header carries a MAC: a non-zero multiplier and an offset that both
     * pass {@link #isValidFloat}.
     *
     * @return {@code null} when valid; otherwise a user-facing error message
     */
    public static String validateMacParameters(float multiplier, float offset) {
        if (multiplier == 0f || !isValidFloat(multiplier)) {
            return "Multiplier must be non-zero with a magnitude within [1e-12, 1e12].";
        }
        if (!isValidFloat(offset)) {
            return "Offset must be 0 or have a magnitude within [1e-12, 1e12].";
        }
        return null;
    }

    protected final void copyCommonStateTo(DensoTable copy) {
        copy.headerAddress = headerAddress;
        copy.ptrX = ptrX;
        copy.countX = countX;
        copy.dataType = dataType;
        copy.typeInferred = typeInferred;
        copy.hasMAC = hasMAC;
        copy.multiplier = multiplier;
        copy.offset = offset;
        copy.valuesX = Arrays.copyOf(valuesX, valuesX.length);
        copy.name = name;
        copy.category = category;
    }

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    public long getHeaderAddress()             { return headerAddress; }
    public void setHeaderAddress(long v)       { headerAddress = v; }

    public long getPtrX()                      { return ptrX; }
    public void setPtrX(long v)               { ptrX = v; }

    public int getCountX()                    { return countX; }
    public void setCountX(int v)              { countX = v; }

    public DensoTableType getDataType()       { return dataType; }
    public void setDataType(DensoTableType v) { dataType = v; }

    public boolean isTypeInferred()           { return typeInferred; }
    public void setTypeInferred(boolean v)    { typeInferred = v; }

    /** Data type name for display, marked with "?" when it was inferred. */
    public String getDataTypeLabel() {
        return dataType.getDisplayName() + (typeInferred ? "?" : "");
    }

    public boolean isHasMAC()                 { return hasMAC; }
    public void setHasMAC(boolean v)          { hasMAC = v; }

    public float getMultiplier()              { return multiplier; }
    public void setMultiplier(float v)        { multiplier = v; }

    public float getOffset()                  { return offset; }
    public void setOffset(float v)            { offset = v; }

    public float[] getValuesX()              { return valuesX; }
    public void setValuesX(float[] v)        { valuesX = v != null ? v : new float[0]; }

    public String getName()                   { return name; }
    public void setName(String v)             { name = v != null ? v : ""; }

    public String getCategory()              { return category; }
    public void setCategory(String v)        { category = v != null ? v : ""; }

    /** Returns the header address as a hex string for display. */
    public String getAddressHex() {
        return "0x" + Long.toHexString(headerAddress).toUpperCase();
    }
}
