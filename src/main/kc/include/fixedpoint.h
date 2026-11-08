#ifndef _FIXEDPOINT_H
#define _FIXEDPOINT_H

#include <stdint.h>

// =============================================================================
// 8.8 Fixed-Point Types (16-Bit: 8-Bit Integer, 8-Bit Fraction)
// =============================================================================

/// 8.8 Signed Fixed-Point type (-128.0 to +127.996)
typedef int16_t fixed8_8;

/// 8.8 Unsigned Fixed-Point type (0.0 to 255.996)
typedef uint16_t ufixed8_8;

/// Construct a fixed8_8 from integer and fraction (0..255)
#define FIXED(i, f) (((int16_t)(i) << 8) | (uint8_t)(f))

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
// 16.8 Extended Fixed-Point Types (24-Bit: 16-Bit Integer, 8-Bit Fraction)
// Ideal for 2D world coordinates, camera tracking, and physics on large maps
// =============================================================================

/// 16.8 Signed Fixed-Point type (16-bit integer part, 8-bit fraction, range -32768.0 to +32767.996)
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

/// Construct a fixed16_8 from 16-bit integer and 8-bit fraction
#define FIXED16_8(i, f) ((fixed16_8){ .frac = (uint8_t)(f), .integer = (int16_t)(i) })

/// Construct a ufixed16_8 from 16-bit unsigned integer and 8-bit fraction
#define UFIXED16_8(i, f) ((ufixed16_8){ .frac = (uint8_t)(f), .integer = (uint16_t)(i) })

/// Extract integer part of a fixed16_8 (0-cycle shift on 6502: direct access to .integer)
#define fixed16_8_int(f) ((f).integer)

/// Extract fractional part of a fixed16_8 (direct access to .frac)
#define fixed16_8_frac(f) ((f).frac)

#endif
