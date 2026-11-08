// Test C23 / modern C features in KickC Enhanced

enum Mode : char {
    MODE_FIRST = 10,
    MODE_SECOND
};

struct Point {
    char x;
    char y;
};

void main() {
    // 1. Digit separators
    unsigned int a = 1_000;
    unsigned char b = 0b1100_0011;
    unsigned int c = 0x12_34;

    // 2. nullptr
    void* ptr = nullptr;

    // 3. Empty initializer {}
    struct Point pt = {};

    // 4. Typed enum
    enum Mode m = MODE_SECOND;

    // Output to screen
    char* const screen = (char*)0x0400;
    screen[0] = (char)a;
    screen[1] = b;
    screen[2] = (char)c;
    screen[3] = (char)(unsigned int)ptr;
    screen[4] = pt.x + pt.y;
    screen[5] = (char)m;
}
