// Test C2Y / C++17 'if' and 'switch' with initializers in KickC Enhanced

char get_value() {
    return 42;
}

void main() {
    char* const screen = (char*)0x0400;

    // Test 1: if with variable declaration
    if(char val = get_value(); val == 42) {
        screen[0] = val; // 42
    } else {
        screen[0] = 0;
    }

    // Test 2: if with initializer and else branch taken
    if(char x = 5; x > 10) {
        screen[1] = 1;
    } else {
        screen[1] = x; // 5
    }

    // Test 3: switch with initializer
    switch(char s = 2; s) {
        case 1:
            screen[2] = 10;
            break;
        case 2:
            screen[2] = 20; // 20
            break;
        default:
            screen[2] = 30;
            break;
    }

    // Test 4: regular if without initializer still works normally
    char regular = 99;
    if(regular > 50) {
        screen[3] = regular; // 99
    }
}
