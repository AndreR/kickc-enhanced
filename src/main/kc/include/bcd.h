/// @file
/// 6502 Binary Coded Decimal (BCD) Arithmetic and Conversion
/// Uses native hardware decimal mode (SED / CLD) with atomic bracketing.
/// Eliminates expensive binary-to-decimal division routines for on-screen score/cash display.
#ifndef _BCD_H
#define _BCD_H

#include <stdint.h>

/// Semantic BCD type annotation
#define __bcd

/// 8-bit BCD type representing 2 decimal digits (00..99).
typedef uint8_t bcd8_t;

/// 16-bit BCD type representing 4 decimal digits (0000..9999).
typedef uint16_t bcd16_t;

/// 32-bit BCD type representing 8 decimal digits (00000000..99999999).
typedef uint32_t bcd32_t;

/// Pack two decimal digits (0..9) into an 8-bit BCD constant/value.
#define BCD8(d1, d0) ((bcd8_t)(((d1) << 4) | (d0)))

/// Pack four decimal digits (0..9) into a 16-bit BCD constant/value.
#define BCD16(d3, d2, d1, d0) ((bcd16_t)(((uint16_t)(d3) << 12) | ((uint16_t)(d2) << 8) | ((uint16_t)(d1) << 4) | (d0)))

/// Add two 8-bit BCD numbers (a + b) with atomic SED/CLD bracketing.
bcd8_t bcd8_add(bcd8_t a, bcd8_t b);

/// Subtract two 8-bit BCD numbers (a - b) with atomic SED/CLD bracketing.
bcd8_t bcd8_sub(bcd8_t a, bcd8_t b);

/// Increment an 8-bit BCD number by 1.
bcd8_t bcd8_inc(bcd8_t a);

/// Decrement an 8-bit BCD number by 1.
bcd8_t bcd8_dec(bcd8_t a);

/// Add two 16-bit BCD numbers (a + b) with atomic SED/CLD bracketing.
bcd16_t bcd16_add(bcd16_t a, bcd16_t b);

/// Subtract two 16-bit BCD numbers (a - b) with atomic SED/CLD bracketing.
bcd16_t bcd16_sub(bcd16_t a, bcd16_t b);

/// Increment a 16-bit BCD number by 1.
bcd16_t bcd16_inc(bcd16_t a);

/// Decrement a 16-bit BCD number by 1.
bcd16_t bcd16_dec(bcd16_t a);

/// Add two 32-bit BCD numbers (a + b) with atomic SED/CLD bracketing.
bcd32_t bcd32_add(bcd32_t a, bcd32_t b);

/// Subtract two 32-bit BCD numbers (a - b) with atomic SED/CLD bracketing.
bcd32_t bcd32_sub(bcd32_t a, bcd32_t b);

/// Convert an 8-bit BCD number to a 2-character ASCII string (with null terminator).
/// Performs ZERO divisions.
void bcd8_to_str(bcd8_t val, char* buf);

/// Convert a 16-bit BCD number to a 4-character ASCII string (with null terminator).
/// Performs ZERO divisions.
void bcd16_to_str(bcd16_t val, char* buf);

/// Convert a 32-bit BCD number to an 8-character ASCII string (with null terminator).
/// Performs ZERO divisions.
void bcd32_to_str(bcd32_t val, char* buf);

/// Convert a standard binary byte (0..99) to 8-bit BCD.
bcd8_t bin_to_bcd8(uint8_t val);

/// Convert an 8-bit BCD number to a standard binary byte.
uint8_t bcd8_to_bin(bcd8_t bcd);

#endif
