// QA-21: Test __countdown loop optimization and clean ASM generation
// Verifies that:
// 1. __countdown loops compile cleanly to dex/dey followed by bpl.
// 2. No extraneous adc #1 instructions or redundant cpx/cpy #0 exist.
// 3. Loop indices and iteration counts are correct.
  // Commodore 64 PRG executable file
.file [name="qa21-countdown-loop.prg", type="prg", segments="Program"]
.segmentdef Program [segments="Basic, Code, Data"]
.segmentdef Basic [start=$0801]
.segmentdef Code [start=$80d]
.segmentdef Data [startAfter="Code"]
.segmentdef StackRam [start=$0100, min=$0100, max=$01af]
.segment Basic
:BasicUpstart(main)
  .label screen = $400
.segment Code
main: {
    // test_simple_countdown()
    jsr test_simple_countdown
    // test_array_countdown()
    jsr test_array_countdown
    // if(screen[0] != 32 || screen[39] != 32)
    lda #$20
    cmp screen
    bne __b4
    cmp screen+$27
    beq __b1
  __b4:
    ldx #0
    .byte $2c

  __b1:
    ldx #1
    // if(buffer[0] != 1 || buffer[9] != 10)
    txa
    cmp buffer
    bne __b5
    lda #$a
    cmp buffer+9
    beq __b2
  __b5:
    ldx #0
  __b2:
    // if(pass)
    cpx #0
    bne __b3
    // *((char*)0xd020) = 2
    lda #2
    sta $d020
    // screen[0] = 'F'
    // Red = FAIL
    lda #'F'
    sta screen
    // screen[1] = 'A'
    lda #'A'
    sta screen+1
    // screen[2] = 'I'
    lda #'I'
    sta screen+2
    // screen[3] = 'L'
    lda #'L'
    sta screen+3
    // }
    rts
  __b3:
    // *((char*)0xd020) = 5
    lda #5
    sta $d020
    // screen[0] = 'P'
    // Green = PASS
    lda #'P'
    sta screen
    // screen[1] = 'A'
    lda #'A'
    sta screen+1
    // screen[2] = 'S'
    lda #'S'
    sta screen+2
    // screen[3] = 'S'
    sta screen+3
    rts
}
test_simple_countdown: {
    ldx #$27
  __b1:
    // screen[i] = 32
    lda #$20
    sta screen,x
    // __countdown for(char i = 0; i < 40; i++)
    dex
    bpl __b1
    // }
    rts
}
test_array_countdown: {
    ldx #9
  __b1:
    // i + 1
    txa
    clc
    adc #1
    // buffer[i] = (char)(i + 1)
    sta buffer,x
    // __countdown for(char i = 0; i < 10; i++)
    dex
    bpl __b1
    // }
    rts
}
.segment Data
  buffer: .fill $10, 0
