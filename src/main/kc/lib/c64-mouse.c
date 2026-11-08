// Commodore 1351 Proportional Mouse Driver with Joystick Fallback
#include <c64-mouse.h>
#include <c64.h>

uint16_t mouse_x = 160;
uint8_t mouse_y = 100;
uint8_t mouse_col = 20;
uint8_t mouse_row = 12;

uint8_t mouse_buttons = 0;
uint8_t mouse_clicked = 0;
uint8_t mouse_is_1351 = 0;

static uint8_t mouse_port = 1;
static uint8_t last_pot_x = 0;
static uint8_t last_pot_y = 0;
static uint8_t last_buttons = 0;

const uint8_t MOUSE_SPRITE_POINTER[64] = {
    0b11000000, 0b00000000, 0b00000000,
    0b11100000, 0b00000000, 0b00000000,
    0b11110000, 0b00000000, 0b00000000,
    0b11111000, 0b00000000, 0b00000000,
    0b11111100, 0b00000000, 0b00000000,
    0b11111110, 0b00000000, 0b00000000,
    0b11111111, 0b00000000, 0b00000000,
    0b11111110, 0b00000000, 0b00000000,
    0b11111000, 0b00000000, 0b00000000,
    0b11011100, 0b00000000, 0b00000000,
    0b10001110, 0b00000000, 0b00000000,
    0b00000111, 0b00000000, 0b00000000,
    0b00000011, 0b10000000, 0b00000000,
    0b00000001, 0b10000000, 0b00000000,
    0b00000000, 0b00000000, 0b00000000,
    0b00000000, 0b00000000, 0b00000000,
    0b00000000, 0b00000000, 0b00000000,
    0b00000000, 0b00000000, 0b00000000,
    0b00000000, 0b00000000, 0b00000000,
    0b00000000, 0b00000000, 0b00000000,
    0b00000000, 0b00000000, 0b00000000,
    0
};

void mouse_init(uint8_t port) {
    mouse_port = port;
    mouse_x = 160;
    mouse_y = 100;
    mouse_col = 20;
    mouse_row = 12;
    mouse_buttons = 0;
    mouse_clicked = 0;
    last_buttons = 0;

    // Read initial pot values
    last_pot_x = (uint8_t)(SID->POT_X >> 1);
    last_pot_y = (uint8_t)(SID->POT_Y >> 1);
    mouse_is_1351 = 1; // Default to 1351 mode
}

void mouse_poll() {
    uint8_t joy;
    if (mouse_port == 1) {
        joy = CIA1->PORT_B;
    } else {
        joy = CIA1->PORT_A;
    }

    // Read current buttons: Left button is Fire (bit 4, active low)
    uint8_t current_buttons = 0;
    if ((joy & 0x10) == 0) {
        current_buttons |= 1; // Left button
    }
    // Right button on 1351 pulls Pot lines or Up bit
    if ((joy & 0x01) == 0 && (joy & 0x02) == 0) {
        current_buttons |= 2; // Right button
    }

    mouse_clicked = current_buttons & (~last_buttons);
    last_buttons = current_buttons;
    mouse_buttons = current_buttons;

    if (mouse_is_1351) {
        // Read 1351 proportional pot values (7-bit resolution)
        uint8_t curr_pot_x = (uint8_t)(SID->POT_X >> 1);
        uint8_t curr_pot_y = (uint8_t)(SID->POT_Y >> 1);

        int8_t dx = (int8_t)((curr_pot_x - last_pot_x) & 0x7f);
        if (dx >= 64) dx -= 128;
        last_pot_x = curr_pot_x;

        int8_t dy = (int8_t)((curr_pot_y - last_pot_y) & 0x7f);
        if (dy >= 64) dy -= 128;
        last_pot_y = curr_pot_y;

        // Apply delta with boundary clamping (320x200)
        if (dx != 0) {
            int16_t nx = (int16_t)mouse_x + dx;
            if (nx < 0) nx = 0;
            if (nx > 319) nx = 319;
            mouse_x = (uint16_t)nx;
        }

        if (dy != 0) {
            // Note: 1351 Y pot increases upwards, so subtract dy to move down on screen
            int16_t ny = (int16_t)mouse_y - dy;
            if (ny < 0) ny = 0;
            if (ny > 199) ny = 199;
            mouse_y = (uint8_t)ny;
        }
    } else {
        // Digital Joystick Fallback (Speed 2 px/frame)
        if ((joy & 0x04) == 0 && mouse_x >= 2) mouse_x -= 2;        // Left
        if ((joy & 0x08) == 0 && mouse_x <= 317) mouse_x += 2;       // Right
        if ((joy & 0x01) == 0 && mouse_y >= 2) mouse_y -= 2;        // Up
        if ((joy & 0x02) == 0 && mouse_y <= 197) mouse_y += 2;       // Down
    }

    // Compute text tile coordinates
    mouse_col = (uint8_t)(mouse_x >> 3);
    mouse_row = mouse_y >> 3;
}
