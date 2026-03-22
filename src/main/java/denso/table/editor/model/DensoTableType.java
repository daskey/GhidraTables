/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor.model;

/**
 * Represents the data type stored in a Denso ECU calibration table.
 * <p>
 * The type code is stored as a little-endian 4-byte field in 2-D headers,
 * or a little-endian 2-byte field in 1-D headers.  Only the first byte
 * carries the type information; the remaining bytes must be zero.
 */
public enum DensoTableType {

    FLOAT(0x00, 4, "Float"),
    UINT8(0x04, 1, "UInt8"),
    UINT16(0x08, 2, "UInt16"),
    INT8(0x0C, 1, "Int8"),
    INT16(0x10, 2, "Int16"),
    UINT32(0xF1, 4, "UInt32"),
    UNDEFINED(-1, 0, "Undefined");

    /** Byte code stored in the ROM header. */
    private final int code;
    /** Number of bytes occupied by one data element. */
    private final int valueSize;
    /** Human-readable name shown in the UI. */
    private final String displayName;

    DensoTableType(int code, int valueSize, String displayName) {
        this.code = code;
        this.valueSize = valueSize;
        this.displayName = displayName;
    }

    public int getCode()         { return code; }
    public int getValueSize()    { return valueSize; }
    public String getDisplayName() { return displayName; }

    /** Returns true for all well-defined types. */
    public boolean isValid() { return this != UNDEFINED; }

    /**
     * Resolves a raw type code byte to the matching enum constant.
     * Returns {@link #UNDEFINED} when the code is not recognised.
     */
    public static DensoTableType fromCode(int code) {
        for (DensoTableType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return UNDEFINED;
    }

    /**
     * Converts a raw integer value to the correct signed/unsigned double,
     * taking the storage type into account.
     */
    public double rawToDouble(long raw) {
        return switch (this) {
            case FLOAT   -> Float.intBitsToFloat((int) raw);
            case UINT8   -> raw & 0xFFL;
            case UINT16  -> raw & 0xFFFFL;
            case INT8    -> (byte) raw;
            case INT16   -> (short) raw;
            case UINT32  -> raw & 0xFFFFFFFFL;
            default      -> raw;
        };
    }

    /**
     * Converts a physical double value to a raw long suitable for writing
     * back to the ROM (big-endian bytes will be assembled by the caller).
     */
    public long doubleToRaw(double value) {
        return switch (this) {
            case FLOAT   -> Float.floatToIntBits((float) value) & 0xFFFFFFFFL;
            case UINT8   -> Math.max(0, Math.min(255,   (long) Math.round(value)));
            case UINT16  -> Math.max(0, Math.min(65535, (long) Math.round(value)));
            case INT8    -> (byte) (long) Math.round(value) & 0xFFL;
            case INT16   -> (short) (long) Math.round(value) & 0xFFFFL;
            case UINT32  -> Math.max(0, (long) Math.round(value)) & 0xFFFFFFFFL;
            default      -> (long) Math.round(value);
        };
    }
}
