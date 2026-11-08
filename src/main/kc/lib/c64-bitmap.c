// Simple single-color (320x200) bitmap routines
#include <c64-bitmap.h>
#include <string.h>
#include <stdint.h>

// The address of the bitmap screen (used for colors)
char* bitmap_screen;
// The address of the bitmap graphics (used for pixels)
char* bitmap_gfx;

// Tables for the plotter - initialized by calling bitmap_init();
// Declared non-const so they are placed in writable RAM segments.
char bitmap_plot_ylo[256];
char bitmap_plot_yhi[256];

// Static ROM table for 8-pixel bit masks (saves 248 bytes of RAM)
const char bitmap_plot_bit[8] = { 0x80, 0x40, 0x20, 0x10, 0x08, 0x04, 0x02, 0x01 };

// Initialize bitmap plotting tables
void bitmap_init(char* gfx, char* screen) {
    bitmap_gfx = gfx;
    bitmap_screen = screen;
    char* yoffs = gfx;
    for(uint8_t y : 0..255) {
        bitmap_plot_ylo[y] = (char)((y & 0x7) | BYTE0(yoffs));
        bitmap_plot_yhi[y] = (char)BYTE1(yoffs);
        if((y & 0x7) == 7) {
            yoffs = yoffs + 40 * 8;
        }
    }
}

// Clear all graphics on the bitmap
void bitmap_clear(char bgcol, char fgcol) {
    char col = (char)(fgcol * 0x10 + bgcol);
    memset(bitmap_screen, col, 1000);
    memset(bitmap_gfx, 0, 8000);
}

// Plot a single dot in the bitmap
void bitmap_plot(unsigned int x, uint8_t y) {
    char* plotter = (char*) MAKEWORD( bitmap_plot_yhi[y], bitmap_plot_ylo[y] );
    plotter += ( x & 0xfff8 );
    *plotter |= bitmap_plot_bit[(char)(x & 7)];
}

void bitmap_unplot(unsigned int x, uint8_t y) {
    char* plotter = (char*) MAKEWORD( bitmap_plot_yhi[y], bitmap_plot_ylo[y] );
    plotter += ( x & 0xfff8 );
    *plotter &= ~(bitmap_plot_bit[(char)(x & 7)]);
}

// Get the absolute value of a 16-bit unsigned number treated as a signed number.
unsigned int abs_u16(unsigned int w) {
    if(BYTE1(w) & 0x80) {
        return -w;
    } else {
        return w;
    }
}

// Get the sign of a 16-bit unsigned number treated as a signed number.
unsigned int sgn_u16(unsigned int w) {
    if(BYTE1(w) & 0x80) {
        return -1;
    } else {
        return 1;
    }
}

// Get the sign of an 8-bit unsigned number treated as a signed number.
uint8_t sgn_u8(uint8_t b) {
    if(b & 0x80) {
        return 0xff;
    } else {
        return 1;
    }
}

// Draw a line on the bitmap using bresenhams algorithm (optimized with 8-bit Y)
void bitmap_line(unsigned int x1, uint8_t y1, unsigned int x2, uint8_t y2) {
    unsigned int x = x1;
    uint8_t y = y1;
    unsigned int dx = abs_u16(x2 - x1);
    uint8_t dy = (uint8_t)abs_u16(y2 - y1);
    if(dx == 0 && dy == 0) {
        bitmap_plot(x, y);
        return;
    }
    unsigned int sx = sgn_u16(x2 - x1);
    uint8_t sy = sgn_u8(y2 - y1);
    if(dx > (unsigned int)dy) {
        // X is the driver
        unsigned int e = (unsigned int)(dy >> 1);
        do  {
            bitmap_plot(x, y);
            x += sx;
            e += dy;
            if(dx < e) {
                y += sy;
                e -= dx;
            }
        } while (x != x2);
    } else {
        // Y is the driver
        unsigned int e = dx >> 1;
        do  {
            bitmap_plot(x, y);
            y += sy;
            e += dx;
            if((unsigned int)dy < e) {
                x += sx;
                e -= dy;
            }
        } while (y != y2);
    }
    bitmap_plot(x, y);
}
