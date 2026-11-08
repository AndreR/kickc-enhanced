#include <c64.h>
#include <stdbool.h>
#include <fixedpoint.h>

bool is_above(char a, char b) {
    return a > b;
}

bool is_match(char a, char b) {
    return a == b;
}

char test_calc(char a, char b, char c) {
    return a + b - c;
}

void main() {
    // 1. Auto Carry-flag boolean returns
    bool c1 = is_above(15, 10);
    bool c2 = is_above(5, 10);
    bool c3 = is_match(7, 7);

    // 2. Leaf-function register calls
    char r = test_calc(20, 10, 5);

    // 3. Fixed-point 8.8 & Literal Suffixes
    fixed8_8 pos = 10.5f8;
    fixed8_8 vel = 2.25f8;
    pos = fixed_add(pos, vel);
    char int_part = (char)fixed_int(pos); // 12
    fixed16_16 lit_16 = { .val = 1.5f16 };
    bool lit_ok = (lit_16.val == 98304L);

    // 4. Built-in delay cycles
    __builtin_delay_cycles(8);

    // 5. Screen verification
    char* screen = (char*)0x0400;
    if(c1 && !c2 && c3 && r == 25 && int_part == 12 && lit_ok) {
        screen[0] = 16; // P
        screen[1] = 1;  // A
        screen[2] = 19; // S
        screen[3] = 19; // S
        screen[4] = 33; // !
    }
    for(;;);
}
