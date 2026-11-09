// QA-22: Test #pragma reserve_zp, #pragma reserve_zp_range, and unreferenced hardcoded ZP variables
// Verifies that:
// 1. #pragma reserve_zp(0xe0..0xeb) reserves the range 0xe0-0xeb.
// 2. #pragma reserve_zp_range(0xec, 0xef) reserves the range 0xec-0xef.
// 3. Unreferenced variables declared with __address(..) __zp are preserved and emitted as labels.
// 4. Regular dynamic variables are not allocated to any reserved or hardcoded ZP address.

#pragma reserve_zp(0xe0..0xeb)
#pragma reserve_zp_range(0xec, 0xef)

__address(0x20) __zp volatile char unused_driver_zp1;
__address(0x21) __zp volatile char unused_driver_zp2;

volatile char* const screen = (char*)0x0400;

void test_sub(char val) {
    screen[0] = val;
}

void main(void) {
    char a = 10;
    char b = 20;
    char c = a + b;
    test_sub(c);
}
