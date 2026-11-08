// Library Implementation of the Seriously Fast Multiplication
// Utilizes Quarter-Square ROM tables from <multiply.h> to save 2,048 bytes of RAM

#include <fastmultiply.h>
#include <multiply.h>

static char _mulf_prepared_a = 0;

// Initialize the mulf_sqr multiplication tables (legacy compatibility: no-op as ROM tables are precomputed)
void mulf_init() {
}

// Prepare for fast multiply with an unsigned char to a unsigned int result
void mulf8u_prepare(char a) {
    _mulf_prepared_a = a;
}

// Calculate fast multiply with a prepared unsigned char to a unsigned int result
unsigned int mulf8u_prepared(char b) {
    return fast_mul8u(_mulf_prepared_a, b);
}

// Fast multiply two unsigned chars to a unsigned int result
unsigned int mulf8u(char a, char b) {
    return fast_mul8u(a, b);
}

// Calculate fast multiply with a prepared unsigned char to a unsigned int result
void mulf8s_prepare(signed char a) {
    mulf8u_prepare((char)a);
}

signed int mulf8s_prepared(signed char b) {
    return mul8s((signed char)_mulf_prepared_a, b);
}

// Fast multiply two signed chars to a unsigned int result
signed int mulf8s(signed char a, signed char b) {
    return mul8s(a, b);
}

// Fast multiply two unsigned ints to a double unsigned int result
unsigned long mulf16u(unsigned int a, unsigned int b) {
    unsigned int p0 = fast_mul8u(BYTE0(a), BYTE0(b));
    unsigned int p1 = fast_mul8u(BYTE1(a), BYTE0(b));
    unsigned int p2 = fast_mul8u(BYTE0(a), BYTE1(b));
    unsigned int p3 = fast_mul8u(BYTE1(a), BYTE1(b));

    unsigned long res = p0;
    res += ((unsigned long)p1) << 8;
    res += ((unsigned long)p2) << 8;
    res += ((unsigned long)p3) << 16;
    return res;
}

// Fast multiply two signed ints to a signed double unsigned int result
signed long mulf16s(signed int a, signed int b) {
    unsigned long m = mulf16u((unsigned int)a, (unsigned int)b);
    if(a<0) {
        WORD1(m) = WORD1(m)-(unsigned int)b;
    }
    if(b<0) {
        WORD1(m) = WORD1(m)-(unsigned int)a;
    }
    return (signed long)m;
}
