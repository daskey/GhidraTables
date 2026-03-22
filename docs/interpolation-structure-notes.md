# Interpolation Structure Notes

These notes come from the live PowerPC firmware in Ghidra MCP, not from the
current scanner implementation. The goal is to keep the table detector aligned
with the code that actually consumes the descriptors.

## Top-level 1D descriptors

### Compact 1D family: 12 bytes

Used by typed helpers such as `interp1d_ushort_only`.

| Offset | Size | Meaning |
| --- | ---: | --- |
| `0x00` | 2 | `axis_count` |
| `0x02` | 2 | type / reserved slot |
| `0x04` | 4 | `x_axis` pointer |
| `0x08` | 4 | `values` pointer |

Ghidra structure evidence:
- `struct_interp1d_ushort_only_p0`
- `struct_interp1d_float_only_p0`

The compact typed helpers do not read the `0x02` slot directly, because the
caller has already selected the concrete value format. In ROM, that slot still
matches the familiar 1D header layout used by the scanner.

### Extended 1D family: 20 bytes

Used by `interp1d_master` and `interp1d_dispatch_by_type`.

| Offset | Size | Meaning |
| --- | ---: | --- |
| `0x00` | 2 | `axis_count` |
| `0x02` | 1 | `value_format` |
| `0x03` | 1 | reserved |
| `0x04` | 4 | `x_axis` pointer |
| `0x08` | 4 | `values` pointer |
| `0x0C` | 4 | packed output scale |
| `0x10` | 4 | packed output bias |

Ghidra structure evidence:
- `interp1d_master_desc_t`

## Top-level 2D descriptors

### Compact 2D family: 20 bytes

This is the no-MAC layout that appears heavily in the calibration-only image.

| Offset | Size | Meaning |
| --- | ---: | --- |
| `0x00` | 2 | `x_count` |
| `0x02` | 2 | `y_count` |
| `0x04` | 4 | `x_axis` pointer |
| `0x08` | 4 | `y_axis` pointer |
| `0x0C` | 4 | `values` pointer |
| `0x10` | 1 | `value_format` |
| `0x11` | 3 | reserved |

Representative compact no-MAC integer tables from `bin/ZF2L101b00G_calibration.bin`
that are easy to misread as float when `value_format == 0x00`:
- `0x0928B520`
- `0x0928B6A0`
- `0x0928B6B4`
- `0x0928A850`
- `0x092903F8`

### Extended 2D family: 28 bytes

Used by `interp2d_master`, `interp2d_dispatch_scaled`, and
`interp2d_dispatch_unscaled`.

| Offset | Size | Meaning |
| --- | ---: | --- |
| `0x00` | 2 | `x_count` |
| `0x02` | 2 | `y_count` |
| `0x04` | 4 | `x_axis` pointer |
| `0x08` | 4 | `y_axis` pointer |
| `0x0C` | 4 | `values` pointer |
| `0x10` | 1 | `value_format` |
| `0x11` | 3 | reserved |
| `0x14` | 4 | packed output scale |
| `0x18` | 4 | packed output bias |

Ghidra structure evidence:
- `interp2d_master_desc_t`
- `struct_interp2d_dispatch_scaled_p0`

## Value format observations

The interpolation code switches on the same byte values already used by the
scanner:

| Format byte | Meaning |
| ---: | --- |
| `0x00` | float32 |
| `0x04` | unsigned 8-bit |
| `0x08` | unsigned 16-bit |
| `0x0C` | signed 8-bit |
| `0x10` | signed 16-bit |

This is visible in the live dispatch functions:
- `interp1d_dispatch_by_type`
- `interp1d_master`
- `interp2d_dispatch_scaled`
- `interp2d_dispatch_unscaled`
- `interp2d_master`

## Detection implications

1. The firmware clearly supports two descriptor families per dimension:
   compact headers without packed scale/bias, and extended headers with them.
2. A raw `value_format` byte of `0x00` is not sufficient to assume float data
   in compact no-MAC tables. The calibration image contains many compact
   integer tables whose payload size is only obvious when bounded by the next
   local descriptor's data pointer.
3. The scanner should therefore treat the local descriptor slab as part of the
   pattern, not just the current header in isolation.
