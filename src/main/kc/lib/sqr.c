// High-performance integer square sqr() and zero-RAM square root sqrt16() / sqrt()

#include <sqr.h>
#include <multiply.h>

// Legacy compatibility; no-op as sqrt16() requires 0 bytes of RAM table
void init_squares() {
}

// Find the square of a char value
unsigned int sqr(char val) {
    return mul8u(val, val);
}

// Digit-by-digit binary square root for 16-bit unsigned integers
// Computes floor(sqrt(val)) in ~150 cycles with 0 bytes RAM table allocation.
unsigned char sqrt16(unsigned int val) {
    unsigned int rem = 0;
    unsigned char root = 0;
    for (char i = 0; i < 8; i++) {
        root <<= 1;
        rem = (rem << 2) | (unsigned int)(val >> 14);
        val <<= 2;
        unsigned int test = ((unsigned int)root << 1) | 1;
        if (rem >= test) {
            rem -= test;
            root |= 1;
        }
    }
    return root;
}

// Find the (integer) square root of an unsigned int value
char sqrt(unsigned int val) {
    return (char)sqrt16(val);
}
