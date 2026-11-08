#include <6502.h>

char* const SCREEN = (char*)0x0400;

void main() {
    SCREEN[0] = 'S';
    delay_cycles(2);
    SCREEN[1] = 'T';
    delay_cycles(3);
    SCREEN[2] = 'A';
    delay_cycles(7);
    SCREEN[3] = 'R';
    delay_cycles(10);
    const unsigned int D = 5;
    delay_cycles(D);
    SCREEN[4] = 'T';
    __builtin_delay_cycles(4);
    SCREEN[5] = '!';
}
