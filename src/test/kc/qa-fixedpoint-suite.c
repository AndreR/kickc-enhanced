// Comprehensive Fixed-Point Math & Transparent Union Assignment Test Suite
#include <fixedpoint.h>

char* const SCREEN = (char*)0x0400;
char* const BORDER = (char*)0xd020;

void main() {
    uint8_t failed = 0;

    // 1. Transparent Union Scalar Assignment & Member Access
    fixed16_16 px = 100.5f16;
    px = 50.25f16;
    if (px.integer != 50) failed |= 1;

    fixed8_24 py = 1.5f24;
    py = 3.25f24;
    if (py.integer != 3) failed |= 2;

    // 2. 8.8 Multiplication: 2.5 * 4.0 = 10.0
    fixed8_8 a = 0x0280; // 2.5
    fixed8_8 b = 0x0400; // 4.0
    fixed8_8 m = fixed_mul(a, b);
    if (m != 0x0a00) failed |= 4; // 10.0 = 0x0a00

    // 3. 8.8 Division: 10.0 / 2.5 = 4.0
    fixed8_8 d = fixed_div(m, a);
    if (d != 0x0400) failed |= 8; // 4.0

    // 4. 8.8 Scalar Mul & Abs
    fixed8_8 s = fixed_mul_int(a, -2); // 2.5 * -2 = -5.0
    if (s != -0x0500) failed |= 16;
    if (fixed_abs(s) != 0x0500) failed |= 32;

    // 5. Trigonometry: sin(0) = 0, sin(64) = 1.0, cos(0) = 1.0
    if (fixed_sin(0) != 0) failed |= 64;
    if (fixed_sin(64) != 0x0100) failed |= 64;
    if (fixed_cos(0) != 0x0100) failed |= 64;

    // 6. Atan2: (y=256, x=0) -> 90 deg = 64 BAM; (y=0, x=256) -> 0 deg = 0 BAM
    uint8_t ang0 = fixed_atan2(0, 0x0100);
    uint8_t ang90 = fixed_atan2(0x0100, 0);
    if (ang0 != 0 || ang90 != 64) failed |= 128;

    // 7. Sqrt: sqrt(16.0) = 4.0 (0x0400)
    fixed8_8 sq = fixed_sqrt(0x1000); // 16.0 = 0x1000
    if (sq != 0x0400) failed |= 1;

    // 8. Lerp: lerp(10.0, 20.0, 0.5) = 15.0
    fixed8_8 l = fixed_lerp(0x0a00, 0x1400, 0x0080);
    if (l != 0x0f00) failed |= 2;

    // 9. Constants check
    if (FIXED_ONE_F8 != 0x0100 || FIXED_ONE_F16 != 0x00010000 || FIXED_ONE_F24 != 0x01000000) failed |= 4;
    if (PI_F8 != 0x0324) failed |= 8;

    if (failed == 0) {
        SCREEN[0] = 16; // 'p'
        SCREEN[1] = 1;  // 'a'
        SCREEN[2] = 19; // 's'
        SCREEN[3] = 19; // 's'
        *BORDER = 5;    // green
    } else {
        SCREEN[0] = 5;  // 'e'
        SCREEN[1] = failed;
        *BORDER = 2;    // red
    }
}
