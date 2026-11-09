// QA-24: Automatic recursion support without explicit __stackcall keyword
// Tests recursive fibonacci, sum_to, and preservation of live variables across recursive calls.

char* const SCREEN = (char*)0x0400;

// Direct recursion: fibonacci (infers __stackcall automatically)
char fib(char n) {
    if (n == 0 || n == 1) {
        return n;
    } else {
        return fib(n - 1) + fib(n - 2);
    }
}

// Direct recursion with 16-bit word: triangular sum 1..n
unsigned int sum_to(unsigned int n) {
    if (n == 0) {
        return 0;
    } else {
        return n + sum_to(n - 1);
    }
}

// Function with multiple local variables alive across recursion
char test_locals(char depth) {
    char a = depth + 10;
    char b = depth + 20;
    char res = 0;
    if (depth > 0) {
        res = test_locals(depth - 1);
    }
    // Verify a and b were not clobbered by child calls
    return res + a + b;
}

void main(void) {
    char f5 = fib(5);              // 5
    unsigned int s5 = sum_to(5);   // 5 + 4 + 3 + 2 + 1 = 15
    char loc = test_locals(2);     // 34 + 32 + 30 = 96

    if (f5 == 5 && s5 == 15 && loc == 96) {
        SCREEN[0] = 'O';
        SCREEN[1] = 'K';
    } else {
        SCREEN[0] = 'E';
        SCREEN[1] = 'R';
        SCREEN[2] = 'R';
    }
}
