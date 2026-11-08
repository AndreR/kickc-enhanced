#include <stdint.h>

volatile char* const SCREEN = (char*)0x0400;
volatile char* const BORDER = (char*)0xd020;

uint4_t buffer[1000];

void main() {
    // 1. Verify sizeof() and countof()
    if (sizeof(buffer) != 500) {
        BORDER[0] = 2; // RED
        return;
    }
    if (countof(buffer) != 1000) {
        BORDER[0] = 2; // RED
        return;
    }

    // 2. Constant indexing write
    buffer[0] = 0x05;
    buffer[1] = 0x0A;

    // Verify raw packed byte: lower nibble 5, upper nibble A -> 0xA5
    char* rawPtr = (char*)buffer;
    if (rawPtr[0] != (char)0xA5) {
        BORDER[0] = 2; // RED
        return;
    }

    // 3. Constant indexing read
    if (buffer[0] != 5) {
        BORDER[0] = 2; // RED
        return;
    }
    if (buffer[1] != 10) {
        BORDER[0] = 2; // RED
        return;
    }

    // 4. Variable indexing write & read
    for (uint8_t i = 0; i < 16; i++) {
        buffer[i] = (uint4_t)i;
    }
    for (uint8_t i = 0; i < 16; i++) {
        if (buffer[i] != i) {
            BORDER[0] = 2; // RED
            return;
        }
    }

    // 5. Compound assignment
    buffer[4] += 3; // was 4, now 7
    if (buffer[4] != 7) {
        BORDER[0] = 2; // RED
        return;
    }

    // SUCCESS! Print PASS! at 0x0400 and set green border
    SCREEN[0] = 'P';
    SCREEN[1] = 'A';
    SCREEN[2] = 'S';
    SCREEN[3] = 'S';
    SCREEN[4] = '!';
    BORDER[0] = 5; // GREEN

    while (1) {}
}
