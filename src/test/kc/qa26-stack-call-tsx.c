// QA-26: Stack parameter unpacking and redundant TSX elimination
// Verifies that multiple stack-call parameters unpacked via STACK_BASE,x eliminate
// redundant TSX instructions.

char* const SCREEN = (char*)0x0400;

__stackcall char multi_param(char a, char b, char c, char d) {
    return a + b + c + d;
}

void main(void) {
    char res = multi_param(10, 20, 30, 40);
    if(res == 100) {
        SCREEN[0] = 'O';
        SCREEN[1] = 'K';
    } else {
        SCREEN[0] = 'E';
        SCREEN[1] = 'R';
        SCREEN[2] = 'R';
    }
}
