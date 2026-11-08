// C standard library ctype.h
#include <ctype.h>
#include <stdbool.h>

char toupper(char ch) {
    if(ch >= 'a' && ch <= 'z') {
        return ch + ('A' - 'a');
    } else {
        return ch;
    }
}

char tolower(char ch) {
    if(ch >= 'A' && ch <= 'Z') {
        return ch + ('a' - 'A');
    } else {
        return ch;
    }
}

bool isdigit(char ch) {
    return (ch >= '0' && ch <= '9');
}

bool isupper(char ch) {
    return (ch >= 'A' && ch <= 'Z');
}

bool islower(char ch) {
    return (ch >= 'a' && ch <= 'z');
}

bool isalpha(char ch) {
    return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
}

bool isalnum(char ch) {
    return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
}

bool isspace(char ch) {
    return (ch == ' ' || ch == 9 || ch == 10 || ch == 13 || ch == 12 || ch == 11);
}

bool isblank(char ch) {
    return (ch == ' ' || ch == 9);
}

bool isxdigit(char ch) {
    return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
}

bool isprint(char ch) {
    return (ch >= 32 && ch < 127);
}

bool isgraph(char ch) {
    return (ch > 32 && ch < 127);
}

bool iscntrl(char ch) {
    return (ch < 32 || ch == 127);
}

bool ispunct(char ch) {
    return isgraph(ch) && !isalnum(ch);
}
