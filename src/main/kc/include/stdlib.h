/// @file
/// C standard library stdlib.h
///
/// Implementation of functions found int C stdlib.h / stdlib.c
#ifndef _STDLIB_H
#define _STDLIB_H

#include <stddef.h>
#include <string.h>
#include <stdint.h>

/// Allocates a block of size chars of memory, returning a pointer to the beginning of the block.
/// The content of the newly allocated block of memory is not initialized, remaining with indeterminate values.
void* malloc(unsigned int size);

/// A block of memory previously allocated by a call to malloc is deallocated, making it available again for further allocations.
/// If ptr is a null pointer, the function does nothing.
void free(void* ptr);

/// Allocates memory and returns a pointer to it. Sets allocated memory to zero.
/// @param nitems This is the number of elements to be allocated.
/// @param size This is the size of elements.
void *calloc(size_t nitems, size_t size);

/// Searches an array of nitems unsigned ints, the initial member of which is pointed to by base, for a member that matches the value key.
/// @param key The value to look for
/// @param items Pointer to the start of the array to search in
/// @param num The number of items in the array
/// @return pointer to an entry in the array that matches the search key
unsigned int* bsearch16u(unsigned int key, unsigned int* items, char num);

/// The different supported radix
enum RADIX { BINARY=2, OCTAL=8, DECIMAL=10, HEXADECIMAL=16 };

/// Converts unsigned 8-bit number value to a string representing it in RADIX format.
void uctoa(unsigned char value, char* buffer, enum RADIX radix);

/// Converts unsigned number value to a string representing it in RADIX format.
/// If the leading digits are zero they are not included in the string.
/// @param value The number to be converted to RADIX
/// @param buffer receives the string representing the number and zero-termination.
/// @param radix The radix to convert the number to (from the enum RADIX)
void utoa(unsigned int value, char* buffer, enum RADIX radix);

/// Converts unsigned number value to a string representing it in RADIX format.
/// If the leading digits are zero they are not included in the string.
/// @param value The number to be converted to RADIX
/// @param buffer receives the string representing the number and zero-termination.
/// @param radix The radix to convert the number to (from the enum RADIX)
void ultoa(unsigned long value, char* buffer, enum RADIX radix);

/// Converts the string argument str to an integer.
int atoi(const char *str);

/// Returns the absolute value of 8-bit signed char x.
inline signed char abs8(signed char x) {
    if(x < 0)
        return -x;
    else
        return x;
}

/// Returns the absolute value of 16-bit signed int x.
inline int abs16(int x) {
    if(x < 0)
        return -x;
    else
        return x;
}

/// Returns the absolute value of long int x.
inline long labs(long x) {
    if(x < 0)
        return -x;
    else
        return x;
}


/// C11 _Generic polymorphic absolute value macro
#define abs(x) _Generic((x), \
    signed char: abs8(x), \
    char: abs8((signed char)(x)), \
    long: labs(x), \
    default: abs16(x) \
)

/// The maximal random value
#define RAND_MAX 65535

/// Returns a pseudo-random number in the range of 0 to RAND_MAX (65535)
unsigned int rand();

/// Seeds the random number generator used by the function rand.
void srand(unsigned int seed);

/// The maximal 8-bit random value
#define RAND8_MAX 255

/// Ultra-fast 8-bit pseudo-random number generator (11 CPU cycles, Galois LFSR period 255)
unsigned char rand8();

/// Seeds the 8-bit random number generator
void srand8(unsigned char seed);

/// The maximal 32-bit random value
#define RAND32_MAX 4294967295UL

/// 32-bit pseudo-random number generator (period 4,294,967,295, Xorshift32)
unsigned long rand32();

/// Seeds the 32-bit random number generator
void srand32(unsigned long seed);

#endif
