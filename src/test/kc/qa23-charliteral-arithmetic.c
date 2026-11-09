// QA-23: Test constant folding for unary and binary arithmetic on character literals
// Verifies that:
// 1. Unary minus -'A' folds correctly.
// 2. Unary plus +'A' folds correctly.
// 3. Bitwise not ~'A' folds correctly.
// 4. Subtraction c - 'A' and 10 - 'A' fold and reorder without "Calculation not implemented".
// 5. Multiplication, division, modulo, and bit shifts on char literals fold cleanly.

volatile char* const screen = (char*)0x0400;

char sub_char(char c) {
    return c - 'A';
}

void main(void) {
    char a = -'A';
    char b = +'A';
    char c = ~'A';
    char d = 100 - 'A';
    char e = 'A' * 2;
    char f = 'B' / 2;
    char g = 'C' % 10;
    char h = 'D' << 1;
    char i = 'D' >> 1;
    char j = sub_char('C');

    screen[0] = a;
    screen[1] = b;
    screen[2] = c;
    screen[3] = d;
    screen[4] = e;
    screen[5] = f;
    screen[6] = g;
    screen[7] = h;
    screen[8] = i;
    screen[9] = j;
}
