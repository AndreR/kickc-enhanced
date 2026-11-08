// Universal ZX02 Decompressor (Einar Saukas format)
#include <zx02.h>

// Zero-Page registers in reserved area $F0..$FF
__address(0xf0) __zp unsigned int zx02_offs;
__address(0xf2) __zp char* zx02_src;
__address(0xf4) __zp char* zx02_dst;
__address(0xf6) __zp unsigned char zx02_bitr;

void zx02_decompress(void* source, void* destination) {
    zx02_src = (char*)source;
    zx02_dst = (char*)destination;

    asm {
        lda $01
        sta $f9
        lda #$00
        sta zx02_offs
        sta zx02_offs + 1
        lda #$80
        sta zx02_bitr
        ldy #$00

    decode_literal:
        jsr get_elias
    cop0:
        lda (zx02_src), y
        inc zx02_src
        bne !+
        inc zx02_src + 1
    !:  sta (zx02_dst), y
        inc zx02_dst
        bne !+
        inc zx02_dst + 1
    !:  dex
        bne cop0

        asl zx02_bitr
        bcs dzx0s_new_offset

    copy_from_last_offset:
        jsr get_elias
    dzx0s_copy:
        lda zx02_dst
        sbc zx02_offs
        sta $f7
        lda zx02_dst + 1
        sbc zx02_offs + 1
        sta $f8

        // Match-copying with RAM banking
        lda #$35
        sta $01
    cop1:
        lda ($f7), y
        inc $f7
        bne !+
        inc $f8
    !:  sta (zx02_dst), y
        inc zx02_dst
        bne !+
        inc zx02_dst + 1
    !:  dex
        bne cop1
        lda $f9
        sta $01

        asl zx02_bitr
        bcc decode_literal

    dzx0s_new_offset:
        jsr get_elias
        beq exit_zx02
        dex
        txa
        lsr
        sta zx02_offs + 1

        lda (zx02_src), y
        inc zx02_src
        bne !+
        inc zx02_src + 1
    !:  ror
        sta zx02_offs

        ldx #1
        jsr elias_skip1
        inx
        bcc dzx0s_copy

    get_elias:
        ldx #1
        bne elias_start
    elias_get:
        asl zx02_bitr
        rol
        tax
    elias_start:
        asl zx02_bitr
        bne elias_skip1
        lda (zx02_src), y
        inc zx02_src
        bne !+
        inc zx02_src + 1
    !:  rol
        sta zx02_bitr
    elias_skip1:
        txa
        bcs elias_get
        rts

    exit_zx02:
    }
}
