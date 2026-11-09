// QA-23: Test constant folding for unary and binary arithmetic on character literals
// Verifies that:
// 1. Unary minus -'A' folds correctly.
// 2. Unary plus +'A' folds correctly.
// 3. Bitwise not ~'A' folds correctly.
// 4. Subtraction c - 'A' and 10 - 'A' fold and reorder without "Calculation not implemented".
// 5. Multiplication, division, modulo, and bit shifts on char literals fold cleanly.
  // Commodore 64 PRG executable file
.file [name="qa23-charliteral-arithmetic.prg", type="prg", segments="Program"]
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
    .const a = -'A'
    .const b = 'A'
    .const c = 'A'^$ff
    .const d = $64-'A'
    .const e = 'A'*2
    .const f = 'B'/2
    .const g = mod('C',$a)
    .const h = 'D'<<1
    .const i = 'D'>>1
    // char j = sub_char('C')
    jsr sub_char
    // screen[0] = a
    lda #a
    sta screen
    // screen[1] = b
    lda #b
    sta screen+1
    // screen[2] = c
    lda #c
    sta screen+2
    // screen[3] = d
    lda #d
    sta screen+3
    // screen[4] = e
    lda #e
    sta screen+4
    // screen[5] = f
    lda #f
    sta screen+5
    // screen[6] = g
    lda #g
    sta screen+6
    // screen[7] = h
    lda #h
    sta screen+7
    // screen[8] = i
    lda #i
    sta screen+8
    // screen[9] = j
    lda #sub_char.return
    sta screen+9
    // }
    rts
}
// char sub_char(char c)
sub_char: {
    .const c = 'C'
    .label return = c-'A'
    rts
}
