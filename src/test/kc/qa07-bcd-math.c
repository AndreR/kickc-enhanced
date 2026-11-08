#include <bcd.h>

char * const screen = (char*)0x0400;

void print_str(char* s, char offset) {
    char i = 0;
    while(s[i]) {
        screen[offset + i] = s[i];
        i++;
    }
}

void main() {
    // 1. 8-bit BCD addition: 45 + 38 = 83
    bcd8_t a8 = 0x45;
    bcd8_t b8 = 0x38;
    bcd8_t sum8 = bcd8_add(a8, b8);
    if(sum8 != 0x83) {
        screen[0] = 'F';
        screen[1] = '1';
        while(1) {}
    }

    // 2. 8-bit BCD subtraction: 83 - 38 = 45
    bcd8_t diff8 = bcd8_sub(sum8, b8);
    if(diff8 != 0x45) {
        screen[0] = 'F';
        screen[2] = '2';
        while(1) {}
    }

    // 3. 16-bit BCD addition: 1999 + 1 = 2000
    bcd16_t a16 = 0x1999;
    bcd16_t b16 = 0x0001;
    bcd16_t sum16 = bcd16_add(a16, b16);
    if(sum16 != 0x2000) {
        screen[0] = 'F';
        screen[3] = '3';
        while(1) {}
    }

    // 4. 16-bit BCD subtraction: 2000 - 5 = 1995
    bcd16_t diff16 = bcd16_sub(sum16, 0x0005);
    if(diff16 != 0x1995) {
        screen[0] = 'F';
        screen[4] = '4';
        while(1) {}
    }

    // 5. 16-bit BCD to string: 1995 -> "1995"
    char buf[5];
    bcd16_to_str(diff16, buf);
    if(buf[0] != '1' || buf[1] != '9' || buf[2] != '9' || buf[3] != '5') {
        screen[0] = 'F';
        screen[5] = '5';
        while(1) {}
    }

    // 6. Binary to BCD and BCD to Binary conversion
    bcd8_t bcd_val = bin_to_bcd8(42);
    if(bcd_val != 0x42) {
        screen[0] = 'F';
        screen[6] = '6';
        while(1) {}
    }
    uint8_t bin_val = bcd8_to_bin(bcd_val);
    if(bin_val != 42) {
        screen[0] = 'F';
        screen[7] = '7';
        while(1) {}
    }

    // All tests passed! Output PASS!
    screen[0] = 'P';
    screen[1] = 'A';
    screen[2] = 'S';
    screen[3] = 'S';
    screen[4] = '!';

    // Print formatted string to row 2
    print_str(buf, 40);

    while(1) {}
}
