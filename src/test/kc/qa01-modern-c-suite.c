#include <c64.h>

static_assert(sizeof(char) == 1);
static_assert(sizeof(int) == 2, "Int must be 16-bit");

enum Status : char {
    IDLE = 10,
    BUSY = 20,
    DONE = 30
};

struct Point {
    char x;
    char y;
};

struct VectorPair {
    union {
        struct { char vx; char vy; };
        int word_val;
    };
};

const char test_table[] = { 1, 2, 3, 4, 5, 6, 7 };
static_assert(countof(test_table) == 7);

#define TYPE_DESC(X) _Generic((X), \
    char: 1, \
    int: 2, \
    default: 0 \
)

void main() {
    enum Status current_status = IDLE;
    char table_len = countof(test_table);
    char type_id = TYPE_DESC(table_len);

    int dec_val = 1_000;
    char bin_val = 0b1010_0101;

    char* ptr = nullptr;
    struct Point pt = {};

    struct VectorPair vec;
    vec.vx = 4;
    vec.vy = 5;

    char found = 0;
    outer_loop: for(char i = 0; i < 5; i++) {
        for(char j = 0; j < 5; j++) {
            if(i == 2 && j == 3) {
                found = 1;
                break outer_loop;
            }
        }
    }

    char if_result = 0;
    if (char scoped = 12; scoped > 10) {
        if_result = scoped;
    }

    char* screen = (char*)0x0400;
    if(found && if_result == 12 && type_id == 1 && vec.vx == 4) {
        screen[0] = 16; // P
        screen[1] = 1;  // A
        screen[2] = 19; // S
        screen[3] = 19; // S
        screen[4] = 33; // !
    }
    for(;;);
}
