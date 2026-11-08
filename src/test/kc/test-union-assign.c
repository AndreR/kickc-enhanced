#include <fixedpoint.h>

char* const SCREEN = (char*)0x0400;
char* const BORDER = (char*)0xd020;

void main() {
    // 1. Initialisierung ohne {}
    fixed16_16 pos_x = 123.456f16;
    
    // 2. Spätere Zuweisung im Code ohne pos_x.val
    pos_x = 45.67f16;

    // 3. Member-Zugriff
    int16_t sx = pos_x.integer; // should be 45

    // 4. fixed8_24
    fixed8_24 pos_y = 1.5f24;
    pos_y = 2.5f24;
    int8_t sy = pos_y.integer; // should be 2

    // 5. Compound assignment
    pos_x += 10.0f16; // should be 55.67f16
    int16_t sx2 = pos_x.integer; // should be 55

    if(sx == 45 && sy == 2 && sx2 == 55) {
        SCREEN[0] = 16; // 'p'
        SCREEN[1] = 1;  // 'a'
        SCREEN[2] = 19; // 's'
        SCREEN[3] = 19; // 's'
        *BORDER = 5; // green
    } else {
        SCREEN[0] = 5;  // 'e'
        *BORDER = 2; // red
    }
}
