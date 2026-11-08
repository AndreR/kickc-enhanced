/// @file
/// Commodore 1351 Proportional Mouse Driver with Joystick Fallback
#ifndef _C64_MOUSE_H
#define _C64_MOUSE_H

#include <stdint.h>

/// Mouse Screen Coordinates (0..319, 0..199)
extern uint16_t mouse_x;
extern uint8_t mouse_y;

/// Mouse Text/Tile Coordinates (0..39, 0..24)
extern uint8_t mouse_col;
extern uint8_t mouse_row;

/// Mouse Buttons: Bit 0 = Left Button, Bit 1 = Right Button (1 = Pressed)
extern uint8_t mouse_buttons;

/// Mouse Clicked: Single-frame edge triggers (1 = clicked this frame)
extern uint8_t mouse_clicked;

/// 1 if a genuine 1351 proportional mouse is detected, 0 if joystick fallback is active
extern uint8_t mouse_is_1351;

/// Initialize the mouse driver (clears position, selects Port 1 / Port 2)
/// port: 1 for Control Port 1, 2 for Control Port 2 (Standard for 1351 is Port 1)
void mouse_init(uint8_t port);

/// Poll the mouse position and buttons. Call once per frame (e.g. in VBlank / Raster IRQ).
void mouse_poll();

/// Classic 64-byte arrow sprite for mouse cursor
extern const uint8_t MOUSE_SPRITE_POINTER[64];

#endif
