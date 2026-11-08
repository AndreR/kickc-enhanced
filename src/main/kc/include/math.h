/// @file
/// Standard and Retro-Optimized Math Utilities
#ifndef _MATH_H
#define _MATH_H

#define PI 3.14159265358979323846
#define M_PI 3.14159265358979323846

#ifndef min
#define min(a, b) (((a) < (b)) ? (a) : (b))
#endif

#ifndef max
#define max(a, b) (((a) > (b)) ? (a) : (b))
#endif

#ifndef clamp
#define clamp(val, low, high) (((val) < (low)) ? (low) : (((val) > (high)) ? (high) : (val)))
#endif

/// Linear interpolation between two 8-bit unsigned values: a + (b - a) * t / 256
inline unsigned char lerp8(unsigned char a, unsigned char b, unsigned char t) {
    if (b >= a) {
        return a + (unsigned char)(((unsigned int)(b - a) * t) >> 8);
    } else {
        return a - (unsigned char)(((unsigned int)(a - b) * t) >> 8);
    }
}

/// Count number of set bits in an 8-bit byte (population count)
inline unsigned char popcount8(unsigned char val) {
    val = (val & 0x55) + ((val >> 1) & 0x55);
    val = (val & 0x33) + ((val >> 2) & 0x33);
    return (val + (val >> 4)) & 0x0f;
}

#endif
