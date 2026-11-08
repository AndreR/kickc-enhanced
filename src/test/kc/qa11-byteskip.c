volatile char cond = 1;
char* const SCREEN = (char*)0x0400;

void test_branch_skip() {
    char val;
    if(cond) {
        val = 'Y';
    } else {
        val = 'N';
    }
    SCREEN[0] = val;
}

void main() {
    test_branch_skip();
    cond = 0;
    test_branch_skip();
    // SCREEN[0] should be 'N' after second call
}
