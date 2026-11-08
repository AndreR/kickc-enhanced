#include <c64.h>
#include <division.h>

#pragma data_seg(StackRam)
char stack_fast_buffer[32];
#pragma data_seg(Data)

void main() {
    char all_correct = 1;

    // Test division & modulo from <division.h>
    for(char i = 0; i < 200; i += 7) {
        char div10 = div8u(i, 10);
        char mod10 = rem8u;
        if(div10 * 10 + mod10 != i) {
            all_correct = 0;
        }

        char div7 = div8u(i, 7);
        char mod7 = rem8u;
        if(div7 * 7 + mod7 != i) {
            all_correct = 0;
        }

        char div25 = div8u(i, 25);
        char mod25 = rem8u;
        if(div25 * 25 + mod25 != i) {
            all_correct = 0;
        }
    }

    // Test compact constant multiplication
    int m24 = (int)10 * 24;
    int m40 = (int)15 * 40;
    int m320 = (int)2 * 320;
    if(m24 != 240 || m40 != 600 || m320 != 640) {
        all_correct = 0;
    }

    // Test StackRam access
    stack_fast_buffer[0] = 42;
    if(stack_fast_buffer[0] != 42) {
        all_correct = 0;
    }

    char* screen = (char*)0x0400;
    if(all_correct) {
        screen[0] = 16; // P
        screen[1] = 1;  // A
        screen[2] = 19; // S
        screen[3] = 19; // S
        screen[4] = 33; // !
    }
    for(;;);
}
