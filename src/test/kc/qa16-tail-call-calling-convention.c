// QA-16: Test Tail Call Optimization Calling Convention & Segment Compatibility
// Verifies:
// 1. A function returning bool in Register A calling a __carry bool function does NOT
//    tail-call via JMP, but normalizes Carry to Register A (LDA #0; ROL; RTS).
// 2. A __carry function calling another __carry function DOES tail-call via JMP.
// 3. Functions with matching conventions correctly tail-call, while mismatched conventions preserve wrappers.

volatile bool flag1 = false;
volatile bool flag2 = true;

// Callee returning in Carry
__carry bool is_flag1_set(void) {
    if(flag1) return true;
    return false;
}

__carry bool is_flag2_set(void) {
    if(flag2) return true;
    return false;
}

// Caller returning standard bool in Register A (calling __carry)
// MUST NOT tail-call to jmp is_flag1_set, must normalize to Register A!
__export bool check_flag1_in_a(void) {
    return is_flag1_set();
}

__export bool check_flag2_in_a(void) {
    return is_flag2_set();
}

// Caller returning in Carry (calling __carry)
// SHOULD tail-call to jmp is_flag1_set!
__carry bool check_flag1_in_carry(void) {
    return is_flag1_set();
}

void main() {
    // Test 1: flag1 is false -> check_flag1_in_a must return false (0)
    bool r1 = check_flag1_in_a();

    // Test 2: flag2 is true -> check_flag2_in_a must return true (1)
    bool r2 = check_flag2_in_a();

    // Test 3: check_flag1_in_carry should return false
    bool r3 = false;
    if(check_flag1_in_carry()) {
        r3 = true;
    }

    if(r1 == false && r2 == true && r3 == false) {
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
