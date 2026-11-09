// QA-24c: Outer variable read/written by a procedure called from a recursive procedure
// Verifies that transitive callees in the re-entrant call tree have their outer variables
// converted to LOAD_STORE so that caller does not synthesize uninitialized phi-parameters.

char* const SCREEN = (char*)0x0400;

char outer_bound = 0;
char outer_counter = 0;

char check_bound(char val) {
    if (val >= outer_bound) return 1;
    outer_counter++;
    return 0;
}

char rec_tree(char depth) {
    if (check_bound(depth)) {
        return depth;
    }
    return rec_tree(depth + 1);
}

void caller(void) {
    outer_bound = 5;
    outer_counter = 0;
    char res = rec_tree(1);
    // depth goes 1, 2, 3, 4, 5: check_bound called for 1 (counter=1), 2 (counter=2), 3 (counter=3), 4 (counter=4), 5 (bound met, counter=4)
    if (res == 5 && outer_counter == 4) {
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
