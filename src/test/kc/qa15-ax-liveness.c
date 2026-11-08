// QA-15: Test Call-Site Register X Liveness for AX Calling Convention
// Verifies that a function returning 16-bit is NOT converted to AX return if caller
// holds a live variable in register X across JSR, but IS converted if X is dead.

volatile unsigned int result_sum = 0;

// Target 1: Caller needs register X across call
unsigned int func_call_with_x_live(unsigned int a) {
    return a + 10;
}

// Target 2: Caller does not need register X across call
unsigned int func_call_x_dead(unsigned int b) {
    return b * 2;
}

void test_caller_x_live() {
    unsigned int sum = 0;
    // Loop variable will be allocated to register X
    for(char i = 0; i < 5; i++) {
        sum += func_call_with_x_live(i);
    }
    result_sum = sum;
}

void test_caller_x_dead() {
    // Single call, no register X live across JSR
    unsigned int val = func_call_x_dead(25);
    result_sum += val;
}

void main() {
    test_caller_x_live();
    test_caller_x_dead();

    // sum for loop: (0+10) + (1+10) + (2+10) + (3+10) + (4+10) = 10+11+12+13+14 = 60
    // dead call: 25 * 2 = 50
    // total = 60 + 50 = 110 (0x006e)
    if(result_sum == 110) {
        *((char*)0xd020) = 5; // Green = PASS
        *((char*)0x0400) = 'P';
        *((char*)0x0401) = 'A';
        *((char*)0x0402) = 'S';
        *((char*)0x0403) = 'S';
    } else {
        *((char*)0xd020) = 2; // Red = FAIL
        *((char*)0x0400) = 'F';
        *((char*)0x0401) = 'A';
        *((char*)0x0402) = 'I';
        *((char*)0x0403) = 'L';
    }
    while(1);
}
