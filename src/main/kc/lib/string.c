// C standard library string.h
// Functions to manipulate C strings and arrays.
#include <string.h>
#include <ctype.h>
#include <stdint.h>

/**
 * @brief Fast copy of data from a source to a destination memory address.
 * Since the amount of bytes to be copied is a byte long, it can be executed very fast.
 * The parameter num can have a value 0, which in case is equal to 256,
 * which allows 256 bytes to be copied using one single byte counter!
 * 
 * @param destination The memory address as the destination.
 * @param source The memory address as the source.
 * @param num The amount of bytes to be copied. A value of 0 will copy 256 bytes!
 * @return char* The resulting destination memory address.
 */
char* memcpy_fast(char* destination, char* source, unsigned char num) {
    unsigned char i = 0;
    do {
        destination[i] = source[i];
        i++;
    } while(i != num);
    return destination;
}

// Copy block of memory (forwards)
// Optimized with 256-byte page copy loops for maximum 6502 throughput (~13 cycles/byte).
void* memcpy( void* destination, void* source, size_t num ) {
    if(num == 0) return destination;
    char* src = (char*)source;
    char* dst = (char*)destination;
    uint8_t pages = (uint8_t)(num >> 8);
    uint8_t rem = (uint8_t)num;
    if(pages > 0) {
        do {
            uint8_t i = 0;
            do {
                dst[i] = src[i];
                i++;
            } while(i != 0);
            src += 256;
            dst += 256;
            pages--;
        } while(pages != 0);
    }
    if(rem > 0) {
        uint8_t i = 0;
        do {
            dst[i] = src[i];
            i++;
        } while(i != rem);
    }
    return destination;
}

// Move block of memory
// Copies the values of num bytes from the location pointed by source to the memory block pointed by destination.
void* memmove( void* destination, void* source, size_t num ) {
    if((uint16_t)destination <= (uint16_t)source) {
        return memcpy(destination, source, num);
    } else {
        // copy backwards
        char* src = (char*)source + num;
        char* dst = (char*)destination + num;
        while(num > 0) {
            *--dst = *--src;
            num--;
        }
    }
    return destination;
}

// Copies the character c (an unsigned char) to the first num characters of the object pointed to by the argument str.
// Optimized with 256-byte page fill loops for maximum 6502 throughput (~10 cycles/byte).
void *memset(void *str, char c, size_t num) {
    if(num == 0) return str;
    char* dst = (char*)str;
    uint8_t pages = (uint8_t)(num >> 8);
    uint8_t rem = (uint8_t)num;
    if(pages > 0) {
        do {
            uint8_t i = 0;
            do {
                dst[i] = c;
                i++;
            } while(i != 0);
            dst += 256;
            pages--;
        } while(pages != 0);
    }
    if(rem > 0) {
        uint8_t i = 0;
        do {
            dst[i] = c;
            i++;
        } while(i != rem);
    }
    return str;
}

/**
 * @brief Fast initialization of an area pointed by a destination memory address to one value c.
 * The parameter num can have a value 0, which in case is equal to 256.
 * 
 * @param destination The destination memory address as the start of the memory area.
 * @param c The byte value initializing the memory area.
 * @param num The amount of bytes to set. A value of 0 will set 256 bytes!
 * @return char* The resulting destination memory address.
 */
char* memset_fast(char* destination, char c, unsigned char num) {
    unsigned char i = 0;
    do {
        destination[i] = c;
        i++;
    } while(i != num);
    return destination;
}

// Copies the C string pointed by source into the array pointed by destination, including the terminating null character (and stopping at that point).
char* strcpy( char* destination, char* source ) {
    char* src = source;
    char* dst = destination;
    while(*src) *dst++ = *src++;
    *dst = 0;
    return destination;
}

/// Copies up to n characters from the string pointed to, by src to dst.
/// In a case where the length of src is less than that of n, the remainder of dst will be padded with null bytes.
char *strncpy(char *dst, const char *src, size_t n) {
    for(size_t i = 0; i < n; i++) {
        char c = *src;
        if(c) src++;
        *dst++ = c;
    }
    return dst;
}

// Concatenates the C string pointed by source into the array pointed by destination, including the terminating null character (and stopping at that point).
char* strcat( char* destination, char* source ) {
    char* src = source;
    char* dst = destination + strlen(destination);
    while(*src) *dst++ = *src++;
    *dst = 0;
    return destination;
}

// Converts a string to uppercase.
char * strupr(char *str) {
    char * src = str;
    while(*src) {
        *src = toupper(*src);
        src++;
    }
    return str;
}

// Computes the length of the string str up to but not including the terminating null character.
// Optimized for 6502 with 8-bit fast path for strings <= 255 chars (~12 cycles/char vs ~32 cycles/char).
size_t strlen(char *str) {
    char len = 0;
    while(str[len]) {
        len++;
        if(len == 0) {
            char *p = str + 256;
            size_t long_len = 256;
            while(*p) {
                long_len++;
                p++;
            }
            return long_len;
        }
    }
    return len;
}

// Searches for the first occurrence of the character c (an unsigned char) in the first n bytes of the string pointed to, by the argument str.
void *memchr(const void *str, char c, size_t n) {
    char * ptr = (char*)str;
    for(size_t i = 0; i < n; i++) {
        if(*ptr == c)
            return ptr;
        ptr++;
    }
    return (void*)0;
}

// Locates the first occurrence of c in the string pointed to by str.
char *strchr(const char *str, char c) {
    while(*str) {
        if(*str == c) return (char*)str;
        str++;
    }
    if(c == 0) return (char*)str;
    return (char*)0;
}

// Locates the last occurrence of c in the string pointed to by str.
char *strrchr(const char *str, char c) {
    char *last = (char*)0;
    while(*str) {
        if(*str == c) last = (char*)str;
        str++;
    }
    if(c == 0) return (char*)str;
    return last;
}

// Compares the first n bytes of memory area str1 and memory area str2.
int memcmp(const void *str1, const void *str2, size_t n) {
    for(char *s1 = (char*)str1, *s2 = (char*)str2; n != 0; n--, s1++, s2++) {
        if(*s1 != *s2)
            return (int)(signed char)(*s1 - *s2);
    }
    return 0;
}

// Compares the string pointed to by str1 to the string pointed to by str2.
int strcmp(const char *str1, const char *str2) {
    char *s1 = (char*)str1, *s2 = (char*)str2;
    while(*s1 == *s2) {
        if(*s1 == 0) return 0;
        s1++; s2++;
    }
    return (int)(signed char)(*s1 - *s2);
}

// Compares at most the first n bytes of str1 and str2.
int strncmp(const char *str1, const char *str2, size_t n) {
    char *s1 = (char*)str1, *s2 = (char*)str2;
    while(*s1 == *s2) {
        n--;
        if(*s1 == 0 || n == 0) return 0;
        s1++; s2++;
    }
    return (int)(signed char)(*s1 - *s2);
}

char* strerror(int errnum) {
    return __errno_error;
}
