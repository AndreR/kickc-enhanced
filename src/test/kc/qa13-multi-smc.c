// Test Multi-Target Pointer-to-SMC optimization in KickC Enhanced

char buffer1[16] = { 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130, 140, 150, 160 };
char* const SCREEN = (char*)0x0400;
char* const BORDER = (char*)0xd020;

void test_multi_deref() {
    char* src = buffer1;
    char sum = 0;
    for(char i = 0; i < 8; i++) {
        // Site 1: read first element
        char a = *src++;
        // Site 2: read second element with another dereference
        char b = *src++;
        sum += a + b;
    }
    SCREEN[0] = sum;
}

void main() {
    test_multi_deref();
    // Sum of 10..160 = 1360. In 8-bit char: 1360 % 256 = 80.
    if(SCREEN[0] == 80) {
        SCREEN[1] = 'P';
        SCREEN[2] = 'A';
        SCREEN[3] = 'S';
        SCREEN[4] = 'S';
        *BORDER = 5; // green
    } else {
        SCREEN[1] = 'F';
        SCREEN[2] = 'A';
        SCREEN[3] = 'I';
        SCREEN[4] = 'L';
        *BORDER = 2; // red
    }
}
