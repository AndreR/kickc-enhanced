// C standard library stdlib.h
// Implementation of functions found int C stdlib.h / stdlib.c
#include <stdlib.h>
#include <string.h>

// Top of the heap used by malloc()
unsigned char* HEAP_TOP = (char*)0xa000;

// Head of the heap. Moved backward each malloc()
unsigned char* heap_head = HEAP_TOP;

// Allocates a block of size chars of memory, returning a pointer to the beginning of the block.
// The content of the newly allocated block of memory is not initialized, remaining with indeterminate values.
void* malloc(unsigned int size) {
    unsigned char* mem = heap_head-size;
    heap_head = mem;
    return mem;
}

// A block of memory previously allocated by a call to malloc is deallocated, making it available again for further allocations.
// If ptr is a null pointer, the function does nothing.
void free(void* ptr) {
    // TODO: So far no support for freeing stuff
}

// Allocates memory and returns a pointer to it. Sets allocated memory to zero.
// - nitems − This is the number of elements to be allocated.
// - size − This is the size of elements.
void *calloc(size_t nitems, size_t size) {
    void* mem = malloc(nitems*size);
    memset(mem, 0, nitems*size);
    return mem;
}

// Searches an array of nitems unsigned ints, the initial member of which is pointed to by base, for a member that matches the value key.
// - key - The value to look for
// - items - Pointer to the start of the array to search in
// - num - The number of items in the array
// Returns pointer to an entry in the array that matches the search key
unsigned int* bsearch16u(unsigned int key, unsigned int* items, char num) {
    if(num == 0 || key < *items)
        return 0;
	while (num > 0) {
		unsigned int* pivot = items + (num >> 1);
		signed int result = (signed int)key-(signed int)*pivot;
		if (result == 0)
			return pivot;
		if (result > 0) {
			items = pivot+1;
			num--;
		}
		num >>= 1;
	}
	// not found - return closest lower value
    return *items<=key?items:items-1;
}

// The digits used for numbers
char DIGITS[] = "0123456789abcdef"z;

// Values of binary digits
unsigned char RADIX_BINARY_VALUES_CHAR[] = {  0b10000000, 0b01000000, 0b00100000, 0b00010000, 0b00001000, 0b00000100, 0b00000010 };
// Values of octal digits
unsigned char RADIX_OCTAL_VALUES_CHAR[] = { 0x40, 0x8 };
// Values of decimal digits
unsigned char RADIX_DECIMAL_VALUES_CHAR[] = { 100, 10 };
// Values of hexadecimal digits
unsigned char RADIX_HEXADECIMAL_VALUES_CHAR[] = { 0x10 };

// Converts unsigned number value to a string representing it in RADIX format.
// If the leading digits are zero they are not included in the string.
// - value : The number to be converted to RADIX
// - buffer : receives the string representing the number and zero-termination.
// - radix : The radix to convert the number to (from the enum RADIX)
void uctoa(unsigned char value, char* buffer, enum RADIX radix) {
    if(radix == HEXADECIMAL) {
        uint8_t hi = value >> 4;
        uint8_t lo = value & 0x0f;
        if(hi != 0) {
            *buffer++ = DIGITS[hi];
        }
        *buffer++ = DIGITS[lo];
        *buffer = 0;
        return;
    }

    char max_digits;
    unsigned char* digit_values;
    if(radix==DECIMAL) {
        max_digits = 3;
        digit_values = RADIX_DECIMAL_VALUES_CHAR;
    } else if(radix==OCTAL) {
        max_digits = 3;
        digit_values = RADIX_OCTAL_VALUES_CHAR;
    } else if(radix==BINARY) {
        max_digits = 8;
        digit_values = RADIX_BINARY_VALUES_CHAR;
    } else {
        // Unknown radix
        *buffer++ = 'e';
        *buffer++ = 'r';
        *buffer++ = 'r';
        *buffer = 0;
        return;
    }
    char started = 0;
    for( char digit=0; digit<max_digits-1; digit++ ) {
        unsigned char digit_value = digit_values[digit];
        if (started || value >= digit_value){
            value = uctoa_append(buffer++, value, digit_value);
            started = 1;
        }
    }
    *buffer++ = DIGITS[(char)value];
    *buffer = 0;
}

// Used to convert a single digit of an unsigned number value to a string representation
unsigned char uctoa_append(char *buffer, unsigned char value, unsigned char sub){
    char digit = 0;
    while (value >= sub){ digit++; value -= sub; }
    *buffer = DIGITS[digit];
    return value;
}

// Values of binary digits
unsigned int RADIX_BINARY_VALUES[] = { 0b1000000000000000, 0b0100000000000000, 0b0010000000000000, 0b0001000000000000, 0b0000100000000000, 0b0000010000000000, 0b0000001000000000, 0b0000000100000000, 0b0000000010000000, 0b0000000001000000, 0b0000000000100000, 0b0000000000010000, 0b0000000000001000, 0b0000000000000100, 0b0000000000000010 };
// Values of octal digits
unsigned int RADIX_OCTAL_VALUES[] = { 0x8000, 0x1000, 0x200, 0x40, 0x8 };
// Values of decimal digits
unsigned int RADIX_DECIMAL_VALUES[] = { 10000, 1000, 100, 10 };
// Values of hexadecimal digits
unsigned int RADIX_HEXADECIMAL_VALUES[] = { 0x1000, 0x100, 0x10 };

// Converts unsigned number value to a string representing it in RADIX format.
void utoa(unsigned int value, char* buffer, enum RADIX radix) {
    if(radix == HEXADECIMAL) {
        uint8_t d0 = (uint8_t)(value >> 12);
        uint8_t d1 = (uint8_t)((value >> 8) & 0x0f);
        uint8_t d2 = (uint8_t)((value >> 4) & 0x0f);
        uint8_t d3 = (uint8_t)(value & 0x0f);
        char started = 0;
        if(d0) { *buffer++ = DIGITS[d0]; started = 1; }
        if(started || d1) { *buffer++ = DIGITS[d1]; started = 1; }
        if(started || d2) { *buffer++ = DIGITS[d2]; started = 1; }
        *buffer++ = DIGITS[d3];
        *buffer = 0;
        return;
    }

    char max_digits;
    unsigned int* digit_values;
    if(radix==DECIMAL) {
        max_digits = 5;
        digit_values = RADIX_DECIMAL_VALUES;
    } else if(radix==OCTAL) {
        max_digits = 6;
        digit_values = RADIX_OCTAL_VALUES;
    } else if(radix==BINARY) {
        max_digits = 16;
        digit_values = RADIX_BINARY_VALUES;
    } else {
        // Unknown radix
        *buffer++ = 'e';
        *buffer++ = 'r';
        *buffer++ = 'r';
        *buffer = 0;
        return;
    }
    char started = 0;
    for( char digit=0; digit<max_digits-1; digit++ ) {
        unsigned int digit_value = digit_values[digit];
        if (started || value >= digit_value){
            value = utoa_append(buffer++, value, digit_value);
            started = 1;
        }
    }
    *buffer++ = DIGITS[(char)value];
    *buffer = 0;
}

// Used to convert a single digit of an unsigned number value to a string representation
unsigned int utoa_append(char *buffer, unsigned int value, unsigned int sub){
    char digit = 0;
    while (value >= sub){ digit++; value -= sub; }
    *buffer = DIGITS[digit];
    return value;
}

// Values of binary digits
unsigned long RADIX_BINARY_VALUES_LONG[] = { 0b10000000000000000000000000000000, 0b01000000000000000000000000000000, 0b00100000000000000000000000000000, 0b00010000000000000000000000000000, 0b00001000000000000000000000000000, 0b00000100000000000000000000000000, 0b00000010000000000000000000000000, 0b00000001000000000000000000000000, 0b00000000100000000000000000000000, 0b00000000010000000000000000000000, 0b00000000001000000000000000000000, 0b00000000000100000000000000000000, 0b00000000000010000000000000000000, 0b00000000000001000000000000000000, 0b00000000000000100000000000000000, 0b00000000000000010000000000000000, 0b00000000000000001000000000000000, 0b00000000000000000100000000000000, 0b00000000000000000010000000000000, 0b00000000000000000001000000000000, 0b00000000000000000000100000000000, 0b00000000000000000000010000000000, 0b00000000000000000000001000000000, 0b00000000000000000000000100000000, 0b00000000000000000000000010000000, 0b00000000000000000000000001000000, 0b00000000000000000000000000100000, 0b00000000000000000000000000010000, 0b00000000000000000000000000001000, 0b00000000000000000000000000000100, 0b00000000000000000000000000000010 };
// Values of octal digits
unsigned long RADIX_OCTAL_VALUES_LONG[] = { 0x40000000, 0x8000000, 0x1000000, 0x200000, 0x40000, 0x8000, 0x1000, 0x200, 0x40, 0x8 };
// Values of decimal digits
unsigned long RADIX_DECIMAL_VALUES_LONG[] = { 1000000000, 100000000, 10000000, 1000000, 100000, 10000, 1000, 100, 10 };
// Values of hexadecimal digits
unsigned long RADIX_HEXADECIMAL_VALUES_LONG[] = { 0x10000000, 0x1000000, 0x100000, 0x10000, 0x1000, 0x100, 0x10 };

// Converts unsigned number value to a string representing it in RADIX format.
void ultoa(unsigned long value, char* buffer, enum RADIX radix){
    char max_digits;
    unsigned long* digit_values;
    if(radix==DECIMAL) {
        max_digits = 10;
        digit_values = RADIX_DECIMAL_VALUES_LONG;
    } else if(radix==HEXADECIMAL) {
        max_digits = 8;
        digit_values = RADIX_HEXADECIMAL_VALUES_LONG;
    } else if(radix==OCTAL) {
        max_digits = 11;
        digit_values = RADIX_OCTAL_VALUES_LONG;
    } else if(radix==BINARY) {
        max_digits = 32;
        digit_values = RADIX_BINARY_VALUES_LONG;
    } else {
        // Unknown radix
        *buffer++ = 'e';
        *buffer++ = 'r';
        *buffer++ = 'r';
        *buffer = 0;
        return;
    }
    char started = 0;
    for( char digit=0; digit<max_digits-1; digit++ ) {
        unsigned long digit_value = digit_values[digit];
        if (started || value >= digit_value){
            value = ultoa_append(buffer++, value, digit_value);
            started = 1;
        }
    }
    *buffer++ = DIGITS[(char)value];
    *buffer = 0;
}

// Used to convert a single digit of an unsigned number value to a string representation
unsigned long ultoa_append(char *buffer, unsigned long value, unsigned long sub){
    char digit = 0;
    while (value >= sub){ digit++; value -= sub; }
    *buffer = DIGITS[digit];
    return value;
}

// Converts the string argument str to an integer.
int atoi(const char *str) {
    int res = 0; // Initialize result
    char negative = 0; // Initialize sign as positive
    char i = 0; // Initialize index of first digit

    if (str[i] == '-') {
        negative = 1;
        i++;
    }
    // Iterate through all digits and update the result
    for (; str[i]>='0' && str[i]<='9'; ++i)
        res = res * 10 + str[i] - '0';
    // Return result with sign
    if(negative)
        return -res;
    else
        return res;
}

// Returns the absolute value of 8-bit signed char x.
signed char abs8(signed char x) {
    if(x < 0)
        return -x;
    else
        return x;
}

// Returns the absolute value of 16-bit signed int x.
int abs16(int x) {
    if(x < 0)
        return -x;
    else
        return x;
}

// Returns the absolute value of int x (compatibility).

// Returns the absolute value of long int x.
long labs(long x) {
    if(x < 0)
        return -x;
    else
        return x;
}

// The random state variable
volatile unsigned int rand_state = 1;

// Returns a pseudo-random number in the range of 0 to RAND_MAX (65535)
unsigned int rand() {
    rand_state ^= rand_state << 7;
    rand_state ^= rand_state >> 9;
    rand_state ^= rand_state << 8;
    return rand_state;
}

// Seeds the random number generator used by the function rand.
void srand(unsigned int seed) {
    if(seed == 0) seed = 1;
    rand_state = seed;
}

// The 8-bit random state variable
volatile unsigned char rand8_state = 1;

// Seeds the 8-bit random number generator
void srand8(unsigned char seed) {
    if(seed == 0) seed = 1;
    rand8_state = seed;
}

// Ultra-fast 8-bit pseudo-random number generator (11 CPU cycles, Galois LFSR period 255)
unsigned char rand8() {
    asm {
        lda rand8_state
        lsr
        bcc !+
        eor #$b8
    !:
        sta rand8_state
    }
    return rand8_state;
}

// The 32-bit random state variable
volatile unsigned long rand32_state = 1;

// Seeds the 32-bit random number generator
void srand32(unsigned long seed) {
    if(seed == 0) seed = 1;
    rand32_state = seed;
}

// 32-bit pseudo-random number generator (period 4,294,967,295, Xorshift32)
unsigned long rand32() {
    unsigned long x = rand32_state;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    rand32_state = x;
    return x;
}
