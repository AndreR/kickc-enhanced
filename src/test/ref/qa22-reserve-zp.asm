// QA-22: Test #pragma reserve_zp, #pragma reserve_zp_range, and unreferenced hardcoded ZP variables
// Verifies that:
// 1. #pragma reserve_zp(0xe0..0xeb) reserves the range 0xe0-0xeb.
// 2. #pragma reserve_zp_range(0xec, 0xef) reserves the range 0xec-0xef.
// 3. Unreferenced variables declared with __address(..) __zp are preserved and emitted as labels.
// 4. Regular dynamic variables are not allocated to any reserved or hardcoded ZP address.
  // Commodore 64 PRG executable file
.file [name="qa22-reserve-zp.prg", type="prg", segments="Program"]
.segmentdef Program [segments="Basic, Code, Data"]
.segmentdef Basic [start=$0801]
.segmentdef Code [start=$80d]
.segmentdef Data [startAfter="Code"]
.segmentdef StackRam [start=$0100, min=$0100, max=$01af]
.segment Basic
:BasicUpstart(__start)
  .label screen = $400
  .label unused_driver_zp1 = $20
  .label unused_driver_zp2 = $21
.segment Code
__start: {
    // __address(0x20) __zp volatile char unused_driver_zp1
    lda #0
    sta.z unused_driver_zp1
    // __address(0x21) __zp volatile char unused_driver_zp2
    sta.z unused_driver_zp2
    jsr main
    rts
}
main: {
    .const a = $a
    .const b = $14
    .label c = a+b
    // test_sub(c)
    jmp test_sub
    // }
}
// void test_sub(char val)
test_sub: {
    // screen[0] = val
    lda #main.c
    sta screen
    // }
    rts
}
