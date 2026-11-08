/// @file
/// High-performance integer square sqr() and zero-RAM square root sqrt16() / sqrt()

#ifndef _SQR_H
#define _SQR_H

/// Initialize squares table (legacy compatibility; no-op as sqrt16 requires 0 RAM)
void init_squares();

/// Find the square of a char value
unsigned int sqr(char val);

/// Find the 8-bit integer square root of a 16-bit unsigned int value in ~150 cycles with 0 bytes RAM table
unsigned char sqrt16(unsigned int val);

/// Find the (integer) square root of an unsigned int value
char sqrt(unsigned int val);

#endif
