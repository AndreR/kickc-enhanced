// Covert Bitops High-Speed Disk I/O Suite for Commodore 64
// Based on Covert Bitops Loadersystem V3.x by Lasse Öörni (MIT License)

#include <c64-fastio.h>

__address(0xe0) __zp uint8_t fastio_zp_fileOpen;
__address(0xe5) __zp uint16_t fastio_zp_src;
__address(0xe7) __zp uint16_t fastio_zp_bits;
__address(0xea) __zp uint16_t fastio_zp_dest;

// __export char FASTIO_DRIVER[] to ensure linker includes assembler backend
extern char FASTIO_DRIVER[];

FastIOMode fastio_init(bool force_safe_mode) {
    uint8_t mode = 0;
    kickasm(uses force_safe_mode, uses mode) {{
        lda force_safe_mode
        beq !detect+
        sec
        jmp !go+
    !detect:
        clc
    !go:
        jsr InitLoader
        lda loaderMode
        sta mode
    }};
    return (FastIOMode)mode;
}

bool fastio_open(uint8_t filenumber) {
    kickasm(uses filenumber) {{
        lda filenumber
        jsr OpenFile
    }};
    return (fastio_zp_fileOpen != 0);
}

uint8_t fastio_get_byte(void) {
    uint8_t b = 0;
    kickasm(uses b) {{
        jsr GetByte
        sta b
    }};
    return b;
}

bool fastio_is_open(void) {
    return (fastio_zp_fileOpen != 0);
}

bool fastio_load(uint8_t filenumber) {
    bool success = false;
    kickasm(uses filenumber, uses success) {{
        lda filenumber
        jsr LoadUnpacked
        bcs !err+
        lda #1
        sta success
    !err:
    }};
    return success;
}

bool fastio_load_raw(uint8_t filenumber, void* dest) {
    bool success = false;
    uint16_t dst = (uint16_t)dest;
    kickasm(uses filenumber, uses dst, uses success) {{
        lda filenumber
        ldx dst
        ldy dst+1
        jsr LoadUnpackedRaw
        bcs !err+
        lda #1
        sta success
    !err:
    }};
    return success;
}

bool fastio_save(uint8_t filenumber, const void* src, uint16_t length) {
    fastio_zp_src = (uint16_t)src;
    fastio_zp_bits = length;
    kickasm(uses filenumber) {{
        lda filenumber
        jsr SaveFile
    }};
    return (fastio_zp_fileOpen != 0);
}

void fastio_set_sprite_range(uint8_t top_y, uint8_t bottom_y) {
    kickasm(uses top_y, uses bottom_y) {{
        lda top_y
        ldx bottom_y
        jsr SetSpriteRange
    }};
}

void fastio_set_no_sprites(void) {
    kickasm {{
        lda #0
        jsr SetNoSprites
    }};
}

// -----------------------------------------------------------------------------
// Covert Bitops Loadersystem V3.x (KickAssembler Backend)
// -----------------------------------------------------------------------------
__export char FASTIO_DRIVER[] = kickasm(resource "c64-fastio.asm") {{
    #import "c64-fastio.asm"
}};
