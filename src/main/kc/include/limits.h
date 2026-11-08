/// @file
/// C standard library limits.h
///
/// Defines constants with the limits of fundamental integral types for the 6502 architecture.

#ifndef _LIMITS_H
#define _LIMITS_H

/// Number of bits in a char object
#define CHAR_BIT        8

/// Minimum value of signed char type
#define SCHAR_MIN       (-128)
/// Maximum value of signed char type
#define SCHAR_MAX       127
/// Maximum value of unsigned char type
#define UCHAR_MAX       255

/// Minimum value of char type (KickC char is unsigned by default)
#define CHAR_MIN        0
/// Maximum value of char type
#define CHAR_MAX        255

/// Maximum number of bytes in a multibyte character
#define MB_LEN_MAX      1

/// Minimum value of signed short type
#define SHRT_MIN        (-32768)
/// Maximum value of signed short type
#define SHRT_MAX        32767
/// Maximum value of unsigned short type
#define USHRT_MAX       65535U

/// Minimum value of signed int type
#define INT_MIN         (-32768)
/// Maximum value of signed int type
#define INT_MAX         32767
/// Maximum value of unsigned int type
#define UINT_MAX        65535U

/// Minimum value of signed long type (-2^31)
#define LONG_MIN        (-2147483648L)
/// Maximum value of signed long type (2^31 - 1)
#define LONG_MAX        2147483647L
/// Maximum value of unsigned long type (2^32 - 1)
#define ULONG_MAX       4294967295UL

/// Minimum value of signed long long type
#define LLONG_MIN       LONG_MIN
/// Maximum value of signed long long type
#define LLONG_MAX       LONG_MAX
/// Maximum value of unsigned long long type
#define ULLONG_MAX      ULONG_MAX

#endif
