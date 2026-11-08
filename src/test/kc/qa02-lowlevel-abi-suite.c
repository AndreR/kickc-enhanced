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

    // 3. Fixed-point 8.8
    fixed8_8 pos = FIXED(10, 128); // 10.5
    fixed8_8 vel = FIXED(2, 64);   // 2.25
    pos = fixed_add(pos, vel);
    char int_part = (char)fixed_int(pos); // 12 or 13

    // 4. Built-in delay cycles
    __builtin_delay_cycles(8);

    // 5. Screen verification
    char* screen = (char*)0x0400;
    if(c1 && !c2 && c3 && r == 25 && int_part == 12) {
        screen[0] = 16; // P
        screen[1] = 1;  // A
        screen[2] = 19; // S
        screen[3] = 19; // S
        screen[4] = 33; // !
    }
    for(;;);
}
