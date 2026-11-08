/// @file
/// C standard library ctype.h
///
/// Functions that are useful for testing and mapping characters.
#ifndef _CTYPE_H
#define _CTYPE_H

#include <stdbool.h>

/// Convert lowercase character to uppercase.
char toupper(char ch);

/// Convert uppercase character to lowercase.
char tolower(char ch);

/// Checks whether the passed character is a decimal digit ('0'..'9').
bool isdigit(char ch);

/// Checks whether the passed character is an alphabetic letter ('a'..'z' or 'A'..'Z').
bool isalpha(char ch);

/// Checks whether the passed character is alphanumeric (letter or digit).
bool isalnum(char ch);

/// Checks whether the passed character is whitespace (' ', '\t', '\n', '\r', '\f', '\v').
bool isspace(char ch);

/// Checks whether the passed character is a blank character (' ' or '\t').
bool isblank(char ch);

/// Checks whether the passed character is an uppercase letter ('A'..'Z').
bool isupper(char ch);

/// Checks whether the passed character is a lowercase letter ('a'..'z').
bool islower(char ch);

/// Checks whether the passed character is a hexadecimal digit ('0'..'9', 'a'..'f', 'A'..'F').
bool isxdigit(char ch);

/// Checks whether the passed character is printable (including space).
bool isprint(char ch);

/// Checks whether the passed character has graphical representation (printable excluding space).
bool isgraph(char ch);

/// Checks whether the passed character is a control character (< 32 or 127).
bool iscntrl(char ch);

/// Checks whether the passed character is a punctuation character (graphical non-alphanumeric).
bool ispunct(char ch);

#endif
