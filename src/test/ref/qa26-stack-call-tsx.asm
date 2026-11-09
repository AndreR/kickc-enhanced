// QA-26: Stack parameter unpacking and redundant TSX elimination
// Verifies that multiple stack-call parameters unpacked via STACK_BASE,x eliminate
// redundant TSX instructions.
  // Commodore 64 PRG executable file
.file [name="qa26-stack-call-tsx.prg", type="prg", segments="Program"]
.segmentdef Program [segments="Basic, Code, Data"]
.segmentdef Basic [start=$0801]
.segmentdef Code [start=$80d]
.segmentdef Data [startAfter="Code"]
.segmentdef StackRam [start=$0100, min=$0100, max=$01af]
.segment Basic
:BasicUpstart(main)
  .const STACK_BASE = $103
  .label SCREEN = $400
.segment Code
main: {
    // char res = multi_param(10, 20, 30, 40)
    lda #$a
    pha
    lda #$14
    pha
    lda #$1e
    pha
    lda #$28
    pha
    jsr multi_param
    tsx
    txa
    axs #-3
    txs
    pla
    // if(res == 100)
    cmp #$64
    beq __b1
    // SCREEN[0] = 'E'
    lda #'E'
    sta SCREEN
    // SCREEN[1] = 'R'
    lda #'R'
    sta SCREEN+1
    // SCREEN[2] = 'R'
    sta SCREEN+2
    // }
    rts
  __b1:
    // SCREEN[0] = 'O'
    lda #'O'
    sta SCREEN
    // SCREEN[1] = 'K'
    lda #'K'
    sta SCREEN+1
    rts
}
// __register(A) char multi_param(__zp(4) char a, __register(Y) char b, __zp(2) char c, __zp(3) char d)
multi_param: {
    .const OFFSET_STACK_A = 3
    .const OFFSET_STACK_B = 2
    .const OFFSET_STACK_C = 1
    .const OFFSET_STACK_D = 0
    .const OFFSET_STACK_RETURN_3 = 3
    .label a = 4
    .label c = 2
    .label d = 3
    tsx
    lda STACK_BASE+OFFSET_STACK_A,x
    sta.z a
    lda STACK_BASE+OFFSET_STACK_B,x
    tay
    lda STACK_BASE+OFFSET_STACK_C,x
    sta.z c
    lda STACK_BASE+OFFSET_STACK_D,x
    sta.z d
    // a + b
    tya
    clc
    adc.z a
    // a + b + c
    clc
    adc.z c
    // return a + b + c + d;
    clc
    adc.z d
    // }
    sta STACK_BASE+OFFSET_STACK_RETURN_3,x
    rts
}
