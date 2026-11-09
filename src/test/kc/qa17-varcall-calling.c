// QA-17: Test __varcall Calling Convention & Register Weight Calculation
// Verifies:
// 1. Calling a __varcall function with constant parameters does not trigger ClassCastException in PassNCalcVariableRegisterWeight.
// 2. Calling a function under #pragma calling(__varcall) works correctly.
// 3. Proper evaluation of parameters and return values.

__varcall char add_one(char a) {
    return a + 1;
}

#pragma calling(__varcall)
char add_two(char a, char b) {
    return a + b;
}

#pragma calling(__phicall)

void main() {
    volatile char x = 5;
    char r1 = add_one(1);      // Constant argument to __varcall
    char r2 = add_one(x);      // Variable argument to __varcall
    char r3 = add_two(10, 20); // Constant arguments under #pragma calling(__varcall)
    char r4 = add_two(x, 15);  // Mixed arguments

    if(r1 == 2 && r2 == 6 && r3 == 30 && r4 == 20) {
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
}
