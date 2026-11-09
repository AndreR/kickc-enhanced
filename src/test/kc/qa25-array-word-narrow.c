// QA-25: Direct indexed addressing for static arrays <= 256 bytes indexed by word expressions
// Verifies that expressions indexing an array fitting in <= 256 bytes do not synthesize
// an unnecessary 16-bit Zero-Page pointer.

char* const SCREEN = (char*)0x0400;

unsigned int score_table[16] = {
    10, 20, 30, 40, 50, 60, 70, 80,
    90, 100, 110, 120, 130, 140, 150, 160
};

unsigned int get_score(unsigned int idx) {
    return score_table[idx];
}

void main(void) {
    unsigned int sum = 0;
    for(unsigned int i = 0; i < 4; i++) {
        sum += get_score(i * 2);
    }
    // score_table[0]=10 + score_table[2]=30 + score_table[4]=50 + score_table[6]=70 = 160
    if(sum == 160) {
        SCREEN[0] = 'O';
        SCREEN[1] = 'K';
    } else {
        SCREEN[0] = 'E';
        SCREEN[1] = 'R';
        SCREEN[2] = 'R';
    }
}
