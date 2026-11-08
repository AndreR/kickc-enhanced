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
    struct {
        uint8_t frac;     ///< Fractional subpixel part (0..255 representing 0..255/256)
        int16_t integer;  ///< 16-bit signed integer part (-32768..+32767)
    };
    uint8_t bytes[3];
} fixed16_8;

/// 16.8 Unsigned Fixed-Point type (16-bit integer part, 8-bit fraction, range 0.0 to 65535.996)
typedef union {
    struct {
        uint8_t  frac;    ///< Fractional subpixel part (0..255 representing 0..255/256)
        uint16_t integer; ///< 16-bit unsigned integer part (0..65535)
    };
    uint8_t bytes[3];
} ufixed16_8;

/// 8.16 Signed Fixed-Point type (8-bit integer part, 16-bit fraction, range -128.0 to +127.99998)
/// High-precision trigonometry, normalized unit vectors, and sine/cosine direction calculations
typedef union {
    struct {
        uint16_t frac;    ///< 16-bit fractional part (0..65535 representing 0..65535/65536)
        int8_t   integer; ///< 8-bit signed integer part (-128..+127)
    };
    uint8_t bytes[3];
} fixed8_16;

/// 8.16 Unsigned Fixed-Point type (8-bit integer part, 16-bit fraction, range 0.0 to 255.99998)
typedef union {
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
// 32-Bit Fixed-Point Types: 16.16 and 24.8
// =============================================================================

/// 16.16 Signed Fixed-Point type (16-bit integer part, 16-bit fraction, range -32768.0 to +32767.99998)
/// Universal standard for 3D transformation matrices, raycasting, Z-buffers, and high-precision simulation
typedef union {
    struct {
        uint16_t frac;    ///< 16-bit fractional part (0..65535 representing 0..65535/65536)
        int16_t  integer; ///< 16-bit signed integer part (-32768..+32767)
    };
    uint8_t bytes[4];
} fixed16_16;

/// 16.16 Unsigned Fixed-Point type (16-bit integer part, 16-bit fraction, range 0.0 to 65535.99998)
typedef union {
    struct {
        uint16_t frac;    ///< 16-bit fractional part (0..65535 representing 0..65535/65536)
        uint16_t integer; ///< 16-bit unsigned integer part (0..65535)
    };
    uint8_t bytes[4];
} ufixed16_16;

/// 24.8 Signed Fixed-Point type (24-bit integer part, 8-bit fraction, range -8388608.0 to +8388607.996)
/// For procedural open-world maps (> 65k px), global celestial coordinates, or high-precision currency balances
typedef union {
    struct {
        uint8_t  frac;    ///< 8-bit fractional part (0..255 representing 0..255/256)
        uint16_t int_lo;  ///< Lower 16 bits of integer part
        int8_t   int_hi;  ///< Upper 8 bits of integer part
    };
    uint8_t bytes[4];
} fixed24_8;

/// 24.8 Unsigned Fixed-Point type (24-bit integer part, 8-bit fraction, range 0.0 to 16777215.996)
typedef union {
    struct {
        uint8_t  frac;    ///< 8-bit fractional part (0..255 representing 0..255/256)
        uint16_t int_lo;  ///< Lower 16 bits of integer part
        uint8_t  int_hi;  ///< Upper 8 bits of integer part
    };
    uint8_t bytes[4];
} ufixed24_8;

/// Construct a fixed16_16 from 16-bit integer and 16-bit fraction
#define FIXED16_16(i, f) ((fixed16_16){ .frac = (uint16_t)(f), .integer = (int16_t)(i) })
#define UFIXED16_16(i, f) ((ufixed16_16){ .frac = (uint16_t)(f), .integer = (uint16_t)(i) })

#endif
