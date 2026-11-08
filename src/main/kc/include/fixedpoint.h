#ifndef _FIXEDPOINT_H
#define _FIXEDPOINT_H

#include <stdint.h>

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

/// Add two fixed-point numbers (native 16-bit addition)
#define fixed_add(a, b) ((a) + (b))

/// Subtract two fixed-point numbers (native 16-bit subtraction)
#define fixed_sub(a, b) ((a) - (b))

#endif
