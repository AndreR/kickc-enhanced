/// @file
/// Simple single-color (320x200) bitmap routines
#ifndef _C64_BITMAP_H
#define _C64_BITMAP_H

#include <stdint.h>

/// Initialize bitmap plotting tables
void bitmap_init(char* gfx, char* screen);

/// Clear all graphics on the bitmap
/// bgcol - the background color to fill the screen with
/// fgcol - the foreground color to fill the screen with
void bitmap_clear(char bgcol, char fgcol);

/// Plot a single dot in the bitmap
void bitmap_plot(unsigned int x, uint8_t y);

/// Clear a single dot in the bitmap
void bitmap_unplot(unsigned int x, uint8_t y);

/// Draw a line on the bitmap using bresenhams algorithm
void bitmap_line(unsigned int x1, uint8_t y1, unsigned int x2, uint8_t y2);

#endif
