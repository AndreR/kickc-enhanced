// QA-24: Automatic recursion support without explicit __stackcall keyword
// Tests recursive fibonacci, sum_to, and preservation of live variables across recursive calls.
  // Commodore 64 PRG executable file
.file [name="qa24-recursion.prg", type="prg", segments="Program"]
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
    .label f5 = $a
    .label s5 = $b
    // char f5 = fib(5)
    lda #5
    pha
    jsr fib
    pla
    sta.z f5
    // unsigned int s5 = sum_to(5)
    pha
    // 5
    lda #0
    pha
    lda #<5
    pha
    jsr sum_to
    pla
    sta.z s5
    pla
    sta.z s5+1
    pla
    sta.z f5
    // char loc = test_locals(2)
    pha
    lda.z s5+1
    pha
    lda.z s5
    pha
    // 5 + 4 + 3 + 2 + 1 = 15
    lda #2
    pha
    jsr test_locals
    pla
    tax
    pla
    sta.z s5
    pla
    sta.z s5+1
    pla
    sta.z f5
    // if (f5 == 5 && s5 == 15 && loc == 96)
    // 34 + 32 + 30 = 96
    lda #5
    cmp.z f5
    bne __b2
    lda.z s5+1
    bne __b2
    lda.z s5
    cmp #$f
    bne __b2
    cpx #$60
    beq __b1
  __b2:
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
// Function with multiple local variables alive across recursion
// __register(A) char test_locals(__register(Y) char depth)
test_locals: {
    .const OFFSET_STACK_DEPTH = 0
    .const OFFSET_STACK_RETURN_0 = 0
    .label a = 4
    .label b = 5
    tsx
    lda STACK_BASE+OFFSET_STACK_DEPTH,x
    tay
    // char a = depth + 10
    clc
    adc #$a
    sta.z a
    // char b = depth + 20
    tya
    clc
    adc #$14
    sta.z b
    // if (depth > 0)
    cpy #0
    beq __b2
    // depth - 1
    dey
    // test_locals(depth - 1)
    lda.z a
    pha
    lda.z b
    pha
    tya
    pha
    jsr test_locals
    // res = test_locals(depth - 1)
    pla
    tax
    // test_locals(depth - 1)
    pla
    sta.z b
    pla
    sta.z a
    jmp __b1
  __b2:
    ldx #0
  __b1:
    // res + a
    txa
    clc
    adc.z a
    // res + a + b
    clc
    adc.z b
    // }
    tsx
    sta STACK_BASE+OFFSET_STACK_RETURN_0,x
    rts
}
// Direct recursion with 16-bit word: triangular sum 1..n
// __zp(2) unsigned int sum_to(__zp(2) unsigned int n)
sum_to: {
    .const OFFSET_STACK_N = 0
    .const OFFSET_STACK_RETURN_0 = 0
    .label n = 2
    .label __1 = 6
    .label __2 = 8
    .label return = 2
    tsx
    lda STACK_BASE+OFFSET_STACK_N,x
    sta.z n
    lda STACK_BASE+OFFSET_STACK_N+1,x
    sta.z n+1
    // if (n == 0)
    lda.z n
    ora.z n+1
    beq __b1
    // n - 1
    lda.z n
    sec
    sbc #1
    sta.z __1
    lda.z n+1
    sbc #0
    sta.z __1+1
    // sum_to(n - 1)
    lda.z n+1
    pha
    lda.z n
    pha
    lda.z __1+1
    pha
    lda.z __1
    pha
    jsr sum_to
    pla
    sta.z __2
    pla
    sta.z __2+1
    pla
    sta.z n
    pla
    sta.z n+1
    // return n + sum_to(n - 1);
    clc
    lda.z return
    adc.z __2
    sta.z return
    lda.z return+1
    adc.z __2+1
    sta.z return+1
    jmp __breturn
  __b1:
    lda #<0
    sta.z return
    sta.z return+1
  __breturn:
    // }
    tsx
    lda.z return
    sta STACK_BASE+OFFSET_STACK_RETURN_0,x
    lda.z return+1
    sta STACK_BASE+OFFSET_STACK_RETURN_0+1,x
    rts
}
// Direct recursion: fibonacci (infers __stackcall automatically)
// __register(A) char fib(__zp(4) char n)
fib: {
    .const OFFSET_STACK_N = 0
    .const OFFSET_STACK_RETURN_0 = 0
    .label n = 4
    .label __4 = 5
    tsx
    lda STACK_BASE+OFFSET_STACK_N,x
    sta.z n
    // if (n == 0 || n == 1)
    beq __b1
    lda #1
    cmp.z n
    beq __b1
    // n - 1
    ldx.z n
    dex
    // fib(n - 1)
    lda.z n
    pha
    txa
    pha
    jsr fib
    pla
    sta.z __4
    pla
    sta.z n
    // n - 2
    tax
    dex
    dex
    // fib(n - 2)
    lda.z __4
    pha
    txa
    pha
    jsr fib
    pla
    tax
    pla
    sta.z __4
    // return fib(n - 1) + fib(n - 2);
    txa
    clc
    adc.z __4
  __breturn:
    // }
    tsx
    sta STACK_BASE+OFFSET_STACK_RETURN_0,x
    rts
  __b1:
    // return n;
    lda.z n
    jmp __breturn
}
