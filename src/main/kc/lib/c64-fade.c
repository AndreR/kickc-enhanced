// Commodore 64 Perceptual Luminance Screen Fading Suite
#include <c64-fade.h>
#include <c64-raster.h>
#include <stdbool.h>

const char FADE_DOWN_LUT[16] = {
    0,   // 0: BLACK        -> BLACK (0)
    15,  // 1: WHITE        -> LIGHT_GREY (15)
    0,   // 2: RED          -> BLACK (0)
    6,   // 3: CYAN         -> BLUE (6)
    6,   // 4: PURPLE       -> BLUE (6)
    0,   // 5: GREEN        -> BLACK (0)
    0,   // 6: BLUE         -> BLACK (0)
    8,   // 7: YELLOW       -> ORANGE (8)
    9,   // 8: ORANGE       -> BROWN (9)
    0,   // 9: BROWN        -> BLACK (0)
    2,   // 10: LIGHT_RED   -> RED (2)
    0,   // 11: DARK_GREY   -> BLACK (0)
    11,  // 12: GREY        -> DARK_GREY (11)
    5,   // 13: LIGHT_GREEN -> GREEN (5)
    3,   // 14: LIGHT_BLUE  -> CYAN (3)
    12   // 15: LIGHT_GREY  -> GREY (12)
};

const char FADE_DOWN_HIGH_LUT[16] = {
    0x00, 0xF0, 0x00, 0x60, 0x60, 0x00, 0x00, 0x80,
    0x90, 0x00, 0x20, 0x00, 0xB0, 0x50, 0x30, 0xC0
};

const char FADE_IN_MATRIX[64] = {
    // Step 0: 0% brightness (all BLACK)
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    // Step 1: ~25% brightness
    0, 11, 0, 0, 0, 0, 0, 11, 0, 0, 0, 0, 0, 0, 6, 11,
    // Step 2: ~50% brightness
    0, 12, 2, 6, 6, 5, 6, 8, 9, 9, 2, 11, 11, 5, 3, 12,
    // Step 3: ~75% brightness
    0, 15, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
};

const char FADE_IN_MATRIX_HI[64] = {
    // Step 0: 0% brightness
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    // Step 1: ~25% brightness
    0x00, 0xB0, 0x00, 0x00, 0x00, 0x00, 0x00, 0xB0,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x60, 0xB0,
    // Step 2: ~50% brightness
    0x00, 0xC0, 0x20, 0x60, 0x60, 0x50, 0x60, 0x80,
    0x90, 0x90, 0x20, 0xB0, 0xB0, 0x50, 0x30, 0xC0,
    // Step 3: ~75% brightness
    0x00, 0xF0, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70,
    0x80, 0x90, 0xA0, 0xB0, 0xC0, 0xD0, 0xE0, 0xF0
};

void fade_color_ram_step(void) {
    asm {
        ldy #0
    l_cram:
        lda $D800,y
        and #$0f
        tax
        lda FADE_DOWN_LUT,x
        sta $D800,y

        lda $D900,y
        and #$0f
        tax
        lda FADE_DOWN_LUT,x
        sta $D900,y

        lda $DA00,y
        and #$0f
        tax
        lda FADE_DOWN_LUT,x
        sta $DA00,y

        iny
        bne l_cram

    l_cram_rem:
        lda $DB00,y
        and #$0f
        tax
        lda FADE_DOWN_LUT,x
        sta $DB00,y
        iny
        cpy #232
        bne l_cram_rem
    }
}

void fade_screen_ram_step(char* screen) {
    for (unsigned int i = 0; i < 1000; i++) {
        char val = screen[i];
        char lo = val & 0x0f;
        char hi = (char)((unsigned char)val >> 4);
        screen[i] = FADE_DOWN_HIGH_LUT[hi] | FADE_DOWN_LUT[lo];
    }
}

void screen_fade_out(void) {
    for (char step = 0; step < 4; step++) {
        wait_vblank();
        VICII->BORDER_COLOR = FADE_DOWN_LUT[VICII->BORDER_COLOR & 0x0f];
        VICII->BG_COLOR = FADE_DOWN_LUT[VICII->BG_COLOR & 0x0f];
        fade_color_ram_step();
        wait_vblank();
    }
}

void screen_fade_in(char target_border, char target_bg) {
    for (char step = 0; step < 4; step++) {
        char offset = (char)(step << 4);
        wait_vblank();
        VICII->BORDER_COLOR = FADE_IN_MATRIX[offset | (target_border & 0x0f)];
        VICII->BG_COLOR = FADE_IN_MATRIX[offset | (target_bg & 0x0f)];
        wait_vblank();
    }
    VICII->BORDER_COLOR = target_border;
    VICII->BG_COLOR = target_bg;
}
