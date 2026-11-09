// QA-21: Test __countdown loop optimization and clean ASM generation
// Verifies that:
// 1. __countdown loops compile cleanly to dex/dey followed by bpl.
// 2. No extraneous adc #1 instructions or redundant cpx/cpy #0 exist.
// 3. Loop indices and iteration counts are correct.

volatile char* const screen = (char*)0x0400;
char buffer[16];

void test_simple_countdown(void) {
    __countdown for(char i = 0; i < 40; i++) {
        screen[i] = 32;
    }
}

void test_array_countdown(void) {
    __countdown for(char i = 0; i < 10; i++) {
        buffer[i] = (char)(i + 1);
    }
}

void main(void) {
    test_simple_countdown();
    test_array_countdown();

    char pass = 1;

    // Check simple countdown wrote 32 to screen[0] and screen[39]
    if(screen[0] != 32 || screen[39] != 32) {
        pass = 0;
    }

    // Check array countdown: buffer[0] == 1, buffer[9] == 10
    if(buffer[0] != 1 || buffer[9] != 10) {
        pass = 0;
    }

    if(pass) {
        *((char*)0xd020) = 5; // Green = PASS
        screen[0] = 'P';
        screen[1] = 'A';
        screen[2] = 'S';
        screen[3] = 'S';
    } else {
        *((char*)0xd020) = 2; // Red = FAIL
        screen[0] = 'F';
        screen[1] = 'A';
        screen[2] = 'I';
        screen[3] = 'L';
    }
}
