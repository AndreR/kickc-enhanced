// Shared implementation for Commodore machines using memory mapped screen and color map (VIC, VIC-II, TED)
// This requires the following constants to be defined
// - CONIO_WIDTH - The screen width
// - CONIO_HEIGHT - The screen height
// - CONIO_SCREEN_TEXT - The text screen address
// - CONIO_SCREEN_COLORS - The color screen address
// - CONIO_TEXTCOLOR_DEFAULT - The default text color

#include <string.h>
#include <stdbool.h>

// The number of bytes on the screen
#define CONIO_BYTES CONIO_HEIGHT*CONIO_WIDTH

// The current cursor x-position
__ma char conio_cursor_x = 0;
// The current cursor y-position
__ma char conio_cursor_y = 0;
// The current text cursor line start
__ma char *conio_line_text = CONIO_SCREEN_TEXT;

#ifndef __PET8032__
// The current color cursor line start
__ma char *conio_line_color = CONIO_SCREEN_COLORS;
#endif

// The current text color
__ma char conio_textcolor = CONIO_TEXTCOLOR_DEFAULT;
// Is a cursor shown when waiting for input (false: no, true: yes)
__ma bool conio_display_cursor = false;
// Is scrolling enabled when outputting beyond the end of the screen (true: yes, false: no).
// If disabled the cursor just moves back to (0,0) instead
__ma bool conio_scroll_enable = true;

// clears the screen and moves the cursor to the upper left-hand corner of the screen.
// Accelerated via fast page-based memset (~10 cycles/byte).
void clrscr(void) {
    memset(CONIO_SCREEN_TEXT, ' ', CONIO_BYTES);
    #ifndef __PET8032__
    memset(CONIO_SCREEN_COLORS, conio_textcolor, CONIO_BYTES);
    #endif
    conio_cursor_x = 0;
    conio_cursor_y = 0;
    conio_line_text = CONIO_SCREEN_TEXT;
    #ifndef __PET8032__
    conio_line_color = CONIO_SCREEN_COLORS;
    #endif
}

// Set the cursor to the specified position
void gotoxy(unsigned char x, unsigned char y) {
    if(y>CONIO_HEIGHT) y = 0;
    if(x>=CONIO_WIDTH) x = 0;
    conio_cursor_x = x;
    conio_cursor_y = y;
    unsigned int line_offset = (unsigned int)y*CONIO_WIDTH;
    conio_line_text = CONIO_SCREEN_TEXT + line_offset;
    #ifndef __PET8032__
    conio_line_color = CONIO_SCREEN_COLORS + line_offset;
    #endif
}

// Return the current screen size.
void screensize(unsigned char* x, unsigned char* y) {
    *x = CONIO_WIDTH;
    *y = CONIO_HEIGHT;
}

// Return the current screen size X width.
inline char screensizex() {
    return CONIO_WIDTH;
}

// Return the current screen size Y height.
inline char screensizey() {
    return CONIO_HEIGHT;
}

// Return the X position of the cursor
inline unsigned char wherex(void) {
    return conio_cursor_x;
}

// Return the Y position of the cursor
inline unsigned char wherey(void) {
    return conio_cursor_y;
}

// Output one character at the current cursor position
// Moves the cursor forward. Scrolls the entire screen if needed
void cputc(char c) {
    if(c=='\n') {
        cputln();
    } else {
        conio_line_text[conio_cursor_x] = c;
        #ifndef __PET8032__
        conio_line_color[conio_cursor_x] = conio_textcolor;
        #endif
        if(++conio_cursor_x==CONIO_WIDTH)
            cputln();
    }
}

// Print a newline
void cputln() {
    conio_line_text +=  CONIO_WIDTH;
    #ifndef __PET8032__
    conio_line_color += CONIO_WIDTH;
    #endif
    conio_cursor_x = 0;
    conio_cursor_y++;
    cscroll();
}

// Scroll the entire screen if the cursor is beyond the last line
void cscroll() {
    if(conio_cursor_y==CONIO_HEIGHT) {
        if(conio_scroll_enable) {
            memcpy(CONIO_SCREEN_TEXT, CONIO_SCREEN_TEXT+CONIO_WIDTH, CONIO_BYTES-CONIO_WIDTH);
            #ifndef __PET8032__
            memcpy(CONIO_SCREEN_COLORS, CONIO_SCREEN_COLORS+CONIO_WIDTH, CONIO_BYTES-CONIO_WIDTH);
            #endif
            memset(CONIO_SCREEN_TEXT+CONIO_BYTES-CONIO_WIDTH, ' ', CONIO_WIDTH);
            #ifndef __PET8032__
            memset(CONIO_SCREEN_COLORS+CONIO_BYTES-CONIO_WIDTH, conio_textcolor, CONIO_WIDTH);
            #endif
            conio_line_text -= CONIO_WIDTH;
            #ifndef __PET8032__
            conio_line_color -= CONIO_WIDTH;
            #endif
            conio_cursor_y--;
        } else {
            gotoxy(0,0);
        }
    }
}

// Output a NUL-terminated string at the current cursor position
void cputs(const char* s) {
    char c;
    while(c=*s++)
        cputc(c);
}

// Move cursor and output one character
// Same as "gotoxy (x, y); cputc (c);"
void cputcxy(unsigned char x, unsigned char y, char c) {
    gotoxy(x, y);
    cputc(c);
}

// Move cursor and output a NUL-terminated string
// Same as "gotoxy (x, y); puts (s);"
void cputsxy(unsigned char x, unsigned char y, const char* s) {
    gotoxy(x, y);
    cputs(s);
}

// Set the color for text output. The old color setting is returned.
unsigned char textcolor(unsigned char color) {
    #ifndef __PET8032__
    char old = conio_textcolor;
    conio_textcolor = color;
    return old;
    #else
    return WHITE;
    #endif
}

// If onoff is true, a cursor is displayed when waiting for keyboard input.
// If onoff is false, the cursor is hidden when waiting for keyboard input.
// The function returns the old cursor setting.
bool cursor(bool onoff) {
    bool old = conio_display_cursor;
    conio_display_cursor = onoff;
    return old;
}

// If onoff is true, scrolling is enabled when outputting past the end of the screen.
// If onoff is false, scrolling is disabled and the cursor instead moves to (0,0).
// The function returns the old scroll setting.
bool scroll(bool onoff) {
    bool old = conio_scroll_enable;
    conio_scroll_enable = onoff;
    return old;
}
