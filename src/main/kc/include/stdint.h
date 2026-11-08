/// @file
/// C standard library stdint.h
///
/// Defines a set of integral type aliases with specific width requirements, along with macros specifying their limits and macro functions to create values of these types.

#ifndef _STDINT_H
#define _STDINT_H

/// Unsigned integer type with a width of exactly 8 bits
typedef unsigned char uint8_t;
/// Signed integer type with a width of exactly 8 bits
typedef signed char int8_t;
/// Unsigned integer type with a width of exactly 16 bits
typedef unsigned int uint16_t;
/// Signed integer type with a width of exactly 16 bits
typedef signed int int16_t;
/// Unsigned integer type with a width of exactly 32 bits
typedef unsigned long uint32_t;
/// Signed integer type with a width of exactly 32 bits
typedef signed long int32_t;
/// Unsigned integer type with the maximum width supported.
typedef unsigned long uintmax_t;
/// Signed integer type with the maximum width supported.
typedef signed long intmax_t;
/// Integer type capable of holding a pointer
typedef unsigned int uintptr_t;
/// Signed integer type capable of holding a pointer
typedef signed int intptr_t;

// Standard ISO C 7.18.2 Limits of specified-width integer types

/// Minimum value of exactly 8-bit wide signed type
#define INT8_MIN        (-128)
/// Maximum value of exactly 8-bit wide signed type
#define INT8_MAX        127
/// Maximum value of exactly 8-bit wide unsigned type
#define UINT8_MAX       255

/// Minimum value of exactly 16-bit wide signed type
#define INT16_MIN       (-32768)
/// Maximum value of exactly 16-bit wide signed type
#define INT16_MAX       32767
/// Maximum value of exactly 16-bit wide unsigned type
#define UINT16_MAX      65535U

/// Minimum value of exactly 32-bit wide signed type (-2^31)
#define INT32_MIN       (-2147483648L)
/// Maximum value of exactly 32-bit wide signed type (2^31 - 1)
#define INT32_MAX       2147483647L
/// Maximum value of exactly 32-bit wide unsigned type (2^32 - 1)
#define UINT32_MAX      4294967295UL

/// Minimum value of integer type capable of holding a pointer
#define INTPTR_MIN      INT16_MIN
/// Maximum value of integer type capable of holding a pointer
#define INTPTR_MAX      INT16_MAX
/// Maximum value of unsigned integer type capable of holding a pointer
#define UINTPTR_MAX     UINT16_MAX

/// Minimum value of greatest-width signed integer type
#define INTMAX_MIN      INT32_MIN
/// Maximum value of greatest-width signed integer type
#define INTMAX_MAX      INT32_MAX
/// Maximum value of greatest-width unsigned integer type
#define UINTMAX_MAX     UINT32_MAX

// Backwards compatibility definitions for KickC
#define UINT8_MIN       0
#define UINT16_MIN      0U
#define UINT32_MIN      0UL
#define UINTMAX_MIN     0UL

// Standard ISO C 7.18.4 Macros for integer constants
#define INT8_C(c)       (c)
#define INT16_C(c)      (c)
#define INT32_C(c)      (c)
#define UINT8_C(c)      (c)
#define UINT16_C(c)     (c)
#define UINT32_C(c)     (c)

#define INTMAX_C(c)     (c)
#define UINTMAX_C(c)    (c)

#endif
