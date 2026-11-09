// QA-24b: Outer / global variable read inside a recursive (STACK_CALL) procedure
// Ensures the outer variable assignment in caller is not dead-store-eliminated
// and is converted to LOAD_STORE so both caller and callee share the same memory location.

char* const SCREEN = (char*)0x0400;

char outer_limit = 0;

char rec_search(char depth) {
    if (depth >= outer_limit) {
        return depth;
    }
    return rec_search(depth + 1);
}

void caller(void) {
    outer_limit = 4;
    char res = rec_search(1);
    if (res == 4) {
        SCREEN[0] = 'O';
        SCREEN[1] = 'K';
    } else {
        SCREEN[0] = 'E';
        SCREEN[1] = 'R';
        SCREEN[2] = 'R';
    }
}

void main(void) {
    caller();
}
