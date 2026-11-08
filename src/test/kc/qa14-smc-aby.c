// QA-14: Test Pointer-to-SMC with both static Y=0 (ABS) and dynamic indexed Y (ABY)
// Verifies bit-exact execution on C64 RAM.

volatile unsigned int dynamic_offset = 0x0428;
char test_buffer[16] = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 };
char* const SCREEN = (char*)0x0400;
char* const BORDER = (char*)0xd020;

// Function 1: Pure sequential pointer dereference with static Y=0
void test_sequential_smc() {
    char* src = test_buffer;
    char* dst = SCREEN;
    for(char i = 0; i < 4; i++) {
        *dst++ = *src++;
    }
}

// Function 2: Dynamic indexed pointer dereference with dynamic Y (ptr[col])
void test_indexed_smc() {
    char* dst = (char*)dynamic_offset;
    for(char col = 0; col < 4; col++) {
        dst[col] = 'a' + col;
    }
}

void main() {
    test_sequential_smc();
    test_indexed_smc();

    // Verify SCREEN[0..3] == 1, 2, 3, 4
    if(SCREEN[0] != 1 || SCREEN[1] != 2 || SCREEN[2] != 3 || SCREEN[3] != 4) {
        *BORDER = 2; // Red = fail
        while(1);
    }

    // Verify SCREEN[40..43] == 'a', 'b', 'c', 'd'
    if(SCREEN[40] != 'a' || SCREEN[41] != 'b' || SCREEN[42] != 'c' || SCREEN[43] != 'd') {
        *BORDER = 2; // Red = fail
        while(1);
    }

    // Success: border green
    *BORDER = 5; // Green = pass
    SCREEN[80] = 'P';
    SCREEN[81] = 'A';
    SCREEN[82] = 'S';
    SCREEN[83] = 'S';
    while(1);
}
