// QA-18: Test Fixed-Point Literal Target Type Mismatch Compile Error
// Suffix 'f8' is 8.8, 'f16' is 16.16, 'f24' is 8.24.
// Attempting to initialize 16.8 (fixed16_8) with 8.8 literal must fail cleanly.

#include <fixedpoint.h>

void main() {
    fixed16_8 px = 100.5f8;
    *((char*)0x400) = px.frac;
}
