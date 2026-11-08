/// @file c64-fade.h
/// @brief Commodore 64 Perceptual Luminance Screen Fading Suite
///
/// Smooth screen and color transitions based on C64 perceptual brightness curves.

#ifndef _C64_FADE_H
#define _C64_FADE_H

#include <c64.h>

/// 16-entry lookup table mapping each C64 color to the next darker perceptual step (0 = Black)
extern const char FADE_DOWN_LUT[16];

/// 16-entry lookup table with high-nibble shifted darker colors (for HiRes/MCM bitmap screen RAM)
extern const char FADE_DOWN_HIGH_LUT[16];

/// 64-entry lookup matrix: 4 brightness steps (0=black, 1=~25%, 2=~50%, 3=~75%) x 16 colors
extern const char FADE_IN_MATRIX[64];

/// 64-entry high-nibble lookup matrix for bitmap screen RAM
extern const char FADE_IN_MATRIX_HI[64];

/// Decrements all 1000 bytes in Color-RAM ($D800..$DBE7) by one perceptual step towards black
void fade_color_ram_step(void);

/// Decrements 1000 bytes of screen RAM by one perceptual step (both high and low nibbles)
void fade_screen_ram_step(char* screen);

/// Smoothly fades out the current screen (border, background, and color RAM) to black in 4 steps.
void screen_fade_out(void);

/// Smoothly fades in the screen to target border and background colors in 4 steps.
void screen_fade_in(char target_border, char target_bg);

#endif
