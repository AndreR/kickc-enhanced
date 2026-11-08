#ifndef _FIXEDPOINT_H
#define _FIXEDPOINT_H

#include <stdint.h>

// =============================================================================
// 16-Bit Fixed-Point Types: 8.8 (8-Bit Integer, 8-Bit Fraction)
// Standard for single-screen games (< 256 px) and compact physics
// =============================================================================

/// 8.8 Signed Fixed-Point type (-128.0 to +127.996)
typedef int16_t fixed8_8;

/// 8.8 Unsigned Fixed-Point type (0.0 to 255.996)
typedef uint16_t ufixed8_8;

/// Construct a fixed8_8 from integer and fraction (0..255)
#define FIXED(i, f) ((fixed8_8)(((int16_t)(i) << 8) | (uint8_t)(f)))

/// Extract integer part of a fixed8_8 (0-cycle shift on 6502: compiles to direct high-byte load LDA f+1)
#define fixed_int(f) ((int8_t)((fixed8_8)(f) >> 8))

/// Extract integer part of a ufixed8_8 (0-cycle shift on 6502: compiles to direct high-byte load LDA f+1)
#define ufixed_int(f) ((uint8_t)((ufixed8_8)(f) >> 8))

/// Extract fractional part (0..255 representing 0..255/256)
#define fixed_frac(f) ((uint8_t)(f))

/// Add two 8.8 fixed-point numbers (native 16-bit addition)
#define fixed_add(a, b) ((a) + (b))

/// Subtract two 8.8 fixed-point numbers (native 16-bit subtraction)
#define fixed_sub(a, b) ((a) - (b))

// =============================================================================
// 24-Bit Fixed-Point Types: 16.8 and 8.16
// =============================================================================

/// 16.8 Signed Fixed-Point type (16-bit integer part, 8-bit fraction, range -32768.0 to +32767.996)
/// Ideal for 2D world coordinates, camera tracking, and physics on large scrolling maps
typedef union {
    int24_t val;
    struct {
        uint8_t frac;     ///< Fractional subpixel part (0..255 representing 0..255/256)
        int16_t integer;  ///< 16-bit signed integer part (-32768..+32767)
    };
    uint8_t bytes[3];
} fixed16_8;

/// 16.8 Unsigned Fixed-Point type (16-bit integer part, 8-bit fraction, range 0.0 to 65535.996)
typedef union {
    uint24_t val;
    struct {
        uint8_t  frac;    ///< Fractional subpixel part (0..255 representing 0..255/256)
        uint16_t integer; ///< 16-bit unsigned integer part (0..65535)
    };
    uint8_t bytes[3];
} ufixed16_8;

/// 8.16 Signed Fixed-Point type (8-bit integer part, 16-bit fraction, range -128.0 to +127.99998)
/// High-precision trigonometry, normalized unit vectors, and sine/cosine direction calculations
typedef union {
    int24_t val;
    struct {
        uint16_t frac;    ///< 16-bit fractional part (0..65535 representing 0..65535/65536)
        int8_t   integer; ///< 8-bit signed integer part (-128..+127)
    };
    uint8_t bytes[3];
} fixed8_16;

/// 8.16 Unsigned Fixed-Point type (8-bit integer part, 16-bit fraction, range 0.0 to 255.99998)
typedef union {
    uint24_t val;
    struct {
        uint16_t frac;    ///< 16-bit fractional part (0..65535 representing 0..65535/65536)
        uint8_t  integer; ///< 8-bit unsigned integer part (0..255)
    };
    uint8_t bytes[3];
} ufixed8_16;

/// Construct a fixed16_8 from 16-bit integer and 8-bit fraction
#define FIXED16_8(i, f) ((fixed16_8){ .frac = (uint8_t)(f), .integer = (int16_t)(i) })
#define UFIXED16_8(i, f) ((ufixed16_8){ .frac = (uint8_t)(f), .integer = (uint16_t)(i) })

/// Construct a fixed8_16 from 8-bit integer and 16-bit fraction
#define FIXED8_16(i, f) ((fixed8_16){ .frac = (uint16_t)(f), .integer = (int8_t)(i) })
#define UFIXED8_16(i, f) ((ufixed8_16){ .frac = (uint16_t)(f), .integer = (uint8_t)(i) })

// =============================================================================
// 32-Bit Fixed-Point Types: 16.16, 24.8 and 8.24
// =============================================================================

/// 16.16 Signed Fixed-Point type (16-bit integer part, 16-bit fraction, range -32768.0 to +32767.99998)
/// Universal standard for 3D transformation matrices, raycasting, Z-buffers, and high-precision simulation
typedef union {
    int32_t val;
    struct {
        uint16_t frac;    ///< 16-bit fractional part (0..65535 representing 0..65535/65536)
        int16_t  integer; ///< 16-bit signed integer part (-32768..+32767)
    };
    uint8_t bytes[4];
} fixed16_16;

/// 16.16 Unsigned Fixed-Point type (16-bit integer part, 16-bit fraction, range 0.0 to 65535.99998)
typedef union {
    uint32_t val;
    struct {
        uint16_t frac;    ///< 16-bit fractional part (0..65535 representing 0..65535/65536)
        uint16_t integer; ///< 16-bit unsigned integer part (0..65535)
    };
    uint8_t bytes[4];
} ufixed16_16;

/// 24.8 Signed Fixed-Point type (24-bit integer part, 8-bit fraction, range -8388608.0 to +8388607.996)
/// For procedural open-world maps (> 65k px), global celestial coordinates, or high-precision currency balances
typedef union {
    int32_t val;
    struct {
        uint8_t  frac;    ///< 8-bit fractional part (0..255 representing 0..255/256)
        uint16_t int_lo;  ///< Lower 16 bits of integer part
        int8_t   int_hi;  ///< Upper 8 bits of integer part
    };
    uint8_t bytes[4];
} fixed24_8;

/// 24.8 Unsigned Fixed-Point type (24-bit integer part, 8-bit fraction, range 0.0 to 16777215.996)
typedef union {
    uint32_t val;
    struct {
        uint8_t  frac;    ///< 8-bit fractional part (0..255 representing 0..255/256)
        uint16_t int_lo;  ///< Lower 16 bits of integer part
        uint8_t  int_hi;  ///< Upper 8 bits of integer part
    };
    uint8_t bytes[4];
} ufixed24_8;

/// 8.24 Signed Fixed-Point type (8-bit integer part, 24-bit fraction, range -128.0 to +127.99999994)
/// High-precision micro-scale math, deep Mandelbrot/fractal zoomers, audio IIR filter coefficients, and orbital gravity
typedef union {
    int32_t val;
    struct {
        uint24_t frac;    ///< 24-bit fractional part (0..16,777,215 representing 0..16.7M / 16.777.216)
        int8_t   integer; ///< 8-bit signed integer part (-128..+127)
    };
    uint8_t bytes[4];
} fixed8_24;

/// 8.24 Unsigned Fixed-Point type (8-bit integer part, 24-bit fraction, range 0.0 to 255.99999994)
typedef union {
    uint32_t val;
    struct {
        uint24_t frac;    ///< 24-bit fractional part (0..16,777,215 representing 0..16.7M / 16.777.216)
        uint8_t  integer; ///< 8-bit unsigned integer part (0..255)
    };
    uint8_t bytes[4];
} ufixed8_24;

/// Construct a fixed16_16 from 16-bit integer and 16-bit fraction
#define FIXED16_16(i, f) ((fixed16_16){ .frac = (uint16_t)(f), .integer = (int16_t)(i) })
#define UFIXED16_16(i, f) ((ufixed16_16){ .frac = (uint16_t)(f), .integer = (uint16_t)(i) })

/// Construct a fixed24_8 from 24-bit integer and 8-bit fraction
#define FIXED24_8(i_lo, i_hi, f) ((fixed24_8){ .frac = (uint8_t)(f), .int_lo = (uint16_t)(i_lo), .int_hi = (int8_t)(i_hi) })
#define UFIXED24_8(i_lo, i_hi, f) ((ufixed24_8){ .frac = (uint8_t)(f), .int_lo = (uint16_t)(i_lo), .int_hi = (uint8_t)(i_hi) })

/// Construct a fixed8_24 from 8-bit integer and 24-bit fraction
#define FIXED8_24(i, f_lo, f_hi) ((fixed8_24){ .frac = ((uint24_t){(uint16_t)(f_lo), (uint8_t)(f_hi)}), .integer = (int8_t)(i) })
#define UFIXED8_24(i, f_lo, f_hi) ((ufixed8_24){ .frac = ((uint24_t){(uint16_t)(f_lo), (uint8_t)(f_hi)}), .integer = (uint8_t)(i) })

// =============================================================================
// Mathematical, Geometric & Retro Constants for Fixed-Point Types
// Suffixes: _F8 (8.8), _F16 (16.16), _F24 (8.24)
// =============================================================================

// Units & Epsilon
#define FIXED_ONE_F8       0x0100
#define FIXED_ONE_F16      0x00010000
#define FIXED_ONE_F24      0x01000000

#define FIXED_HALF_F8      0x0080
#define FIXED_HALF_F16     0x00008000
#define FIXED_HALF_F24     0x00800000

#define FIXED_QUARTER_F8   0x0040
#define FIXED_QUARTER_F16  0x00004000
#define FIXED_QUARTER_F24  0x00400000

#define FIXED_EPSILON_F8   0x0001
#define FIXED_EPSILON_F16  0x00000001
#define FIXED_EPSILON_F24  0x00000001

// Circle & Angles
#define PI_F8              0x0324
#define PI_F16             0x0003243f
#define PI_F24             0x03243f6b

#define TAU_F8             0x0648
#define TAU_F16            0x0006487f
#define TAU_F24            0x06487ed5

#define HALF_PI_F8         0x0192
#define HALF_PI_F16        0x00019220
#define HALF_PI_F24        0x01921fb5

#define QUARTER_PI_F8      0x00c9
#define QUARTER_PI_F16     0x0000c910
#define QUARTER_PI_F24     0x00c90fdb

#define INV_PI_F8          0x0051
#define INV_PI_F16         0x0000517d
#define INV_PI_F24         0x00517cc2

#define INV_TAU_F8         0x0029
#define INV_TAU_F16        0x000028be
#define INV_TAU_F24        0x0028be61

// Angle Conversions (BAM = Binary Angular Measurement: 256 BAM = 360 deg = 2*PI rad)
#define DEG_TO_RAD_F8      0x0004
#define DEG_TO_RAD_F16     0x00000478
#define DEG_TO_RAD_F24     0x000477d2

#define RAD_TO_DEG_F8      0x394c
#define RAD_TO_DEG_F16     0x00394bb8
#define RAD_TO_DEG_F24     0x394bb835

#define DEG_TO_BAM_F8      0x00b6
#define DEG_TO_BAM_F16     0x0000b60b
#define DEG_TO_BAM_F24     0x00b60b61

#define BAM_TO_DEG_F8      0x0168
#define BAM_TO_DEG_F16     0x00016800
#define BAM_TO_DEG_F24     0x01680000

#define RAD_TO_BAM_F8      0x28be
#define RAD_TO_BAM_F16     0x0028be61
#define RAD_TO_BAM_F24     0x28be60dc

#define BAM_TO_RAD_F8      0x0006
#define BAM_TO_RAD_F16     0x00000648
#define BAM_TO_RAD_F24     0x0006487f

// Geometry & Vectors
#define SQRT2_F8           0x016a
#define SQRT2_F16          0x00016a0a
#define SQRT2_F24          0x016a09e6

#define INV_SQRT2_F8       0x00b5
#define INV_SQRT2_F16      0x0000b505
#define INV_SQRT2_F24      0x00b504f3

#define SQRT3_F8           0x01bb
#define SQRT3_F16          0x0001bb68
#define SQRT3_F24          0x01bb67af

#define PHI_F8             0x019e
#define PHI_F16            0x00019e37
#define PHI_F24            0x019e377a

// Logarithms
#define E_F8               0x02b8
#define E_F16              0x0002b7e1
#define E_F24              0x02b7e151

#define LN2_F8             0x00b1
#define LN2_F16            0x0000b172
#define LN2_F24            0x00b17218

#define LOG2E_F8           0x0171
#define LOG2E_F16          0x00017154
#define LOG2E_F24          0x01715476

// =============================================================================
// Compile-Time Literal Folding Macros
// e.g. fixed16_16 pos = F16_16(123.456);
// =============================================================================

#define F8_8(lit)    ((fixed8_8)(lit))
#define UF8_8(lit)   ((ufixed8_8)(lit))

#define F16_8(lit)   ((fixed16_8){ .val = (int24_t)(lit) })
#define UF16_8(lit)  ((ufixed16_8){ .val = (uint24_t)(lit) })

#define F8_16(lit)   ((fixed8_16){ .val = (int24_t)((int32_t)(lit) * 256) })
#define UF8_16(lit)  ((ufixed8_16){ .val = (uint24_t)((uint32_t)(lit) * 256) })

#define F16_16(lit)  ((fixed16_16){ .val = (int32_t)(lit) * 256 })
#define UF16_16(lit) ((ufixed16_16){ .val = (uint32_t)(lit) * 256 })

#define F24_8(lit)   ((fixed24_8){ .val = (int32_t)(lit) })
#define UF24_8(lit)  ((ufixed24_8){ .val = (uint32_t)(lit) })

#define F8_24(lit)   ((fixed8_24){ .val = (int32_t)(lit) * 65536 })
#define UF8_24(lit)  ((ufixed8_24){ .val = (uint32_t)(lit) * 65536 })

// =============================================================================
// High-Performance Fixed-Point Math Functions
// =============================================================================

// --- 8.8 Arithmetic ---

/// Multiply two 8.8 signed fixed-point numbers (16x16 -> 32-bit product with middle 16-bit extraction)
fixed8_8 fixed_mul(fixed8_8 a, fixed8_8 b);

/// Multiply two 8.8 unsigned fixed-point numbers
ufixed8_8 ufixed_mul(ufixed8_8 a, ufixed8_8 b);

/// Divide two 8.8 signed fixed-point numbers (with 8-bit numerator pre-shift)
fixed8_8 fixed_div(fixed8_8 a, fixed8_8 b);

/// Multiply an 8.8 fixed-point number with an 8-bit signed integer scalar
fixed8_8 fixed_mul_int(fixed8_8 a, int8_t scalar);

/// Absolute value of an 8.8 fixed-point number
fixed8_8 fixed_abs(fixed8_8 a);

/// Linear interpolation between a and b: a + (b - a) * t (t in [0.0, 1.0])
fixed8_8 fixed_lerp(fixed8_8 a, fixed8_8 b, ufixed8_8 t);

// --- 16.16 Arithmetic ---

/// Multiply two 16.16 signed fixed-point numbers
fixed16_16 fixed16_16_mul(fixed16_16 a, fixed16_16 b);

/// Divide two 16.16 signed fixed-point numbers
fixed16_16 fixed16_16_div(fixed16_16 a, fixed16_16 b);

// --- Trigonometry & Geometry ---

/// 8.8 Sine lookup: angle in 0..255 (0..360 deg BAM), returns -1.0 .. +1.0 in 8.8 format
fixed8_8 fixed_sin(uint8_t angle);

/// 8.8 Cosine lookup: angle in 0..255 (0..360 deg BAM)
fixed8_8 fixed_cos(uint8_t angle);

/// Fast 2D angle bearing in 0..255 (0..360 deg BAM) from (x, y) vector
uint8_t fixed_atan2(fixed8_8 y, fixed8_8 x);

/// Fast bit-by-bit restoring square root for 8.8 fixed-point
fixed8_8 fixed_sqrt(fixed8_8 val);

/// Euclidean 2D distance: sqrt(dx^2 + dy^2)
fixed8_8 fixed_dist(fixed8_8 dx, fixed8_8 dy);

// --- Lookup Tables (in fixedpoint.c) ---
extern const uint8_t FIXED_SIN_TAB_LO[256];
extern const uint8_t FIXED_SIN_TAB_HI[256];
extern const uint8_t FIXED_ATAN_TAB[33];

#endif
