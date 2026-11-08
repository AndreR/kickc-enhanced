/// @file
/// Fast and binary multiplication implementations

#ifndef _MULTIPLY_H
#define _MULTIPLY_H

/// Perform binary multiplication of two unsigned 8-bit chars into a 16-bit unsigned int
unsigned int mul8u(char a, char b);

/// Multiply of two signed chars to a signed int
/// Fixes offsets introduced by using unsigned multiplication
signed int mul8s(signed char a, signed char b);

/// Multiply a signed char and an unsigned char (into a signed int)
/// Fixes offsets introduced by using unsigned multiplication
signed int mul8su(signed char a, char b);

/// Perform binary multiplication of two unsigned 16-bit unsigned ints into a 32-bit unsigned long
unsigned long mul16u(unsigned int a, unsigned int b);

/// Multiply of two signed ints to a signed double unsigned int
/// Fixes offsets introduced by using unsigned multiplication
signed long mul16s(signed int a, signed int b);

// ============================================================================
// Quarter-Square Fast Multiplication: a * b = floor((a+b)^2 / 4) - floor((a-b)^2 / 4)
// Constant time (~35-40 cycles), 100% bit-exact for all 65,536 combinations.
// Zero Self-Modifying Code (ROM-safe & re-entrant).
// ============================================================================

/// Perform fast quarter-square multiplication of two unsigned 8-bit chars into a 16-bit unsigned int
unsigned int fast_mul8u(char a, char b);

/// Perform fast quarter-square multiplication of two unsigned 16-bit ints into a 32-bit unsigned long
/// 0 bytes RAM allocation, zero self-modifying code, 100% ROM-/Cartridge-safe.
unsigned long fast_mul16u(unsigned int a, unsigned int b);

/// Perform fast quarter-square multiplication of two signed 16-bit ints into a signed 32-bit signed long
/// 0 bytes RAM allocation, zero self-modifying code, 100% ROM-/Cartridge-safe.
signed long fast_mul16s(signed int a, signed int b);

extern const char FAST_MULTIPLY_SQR_LO[512];
extern const char FAST_MULTIPLY_SQR_HI[512];

#endif
