// QA-24b: Outer / global variable read inside a recursive (STACK_CALL) procedure
// Ensures the outer variable assignment in caller is not dead-store-eliminated
// and is converted to LOAD_STORE so both caller and callee share the same memory location.
  // Commodore 64 PRG executable file
.file [name="qa24b-recursion-outer-read.prg", type="prg", segments="Program"]
.segmentdef Program [segments="Basic, Code, Data"]
.segmentdef Basic [start=$0801]
.segmentdef Code [start=$80d]
.segmentdef Data [startAfter="Code"]
.segmentdef StackRam [start=$0100, min=$0100, max=$01af]
.segment Basic
:BasicUpstart(__start)
  .const STACK_BASE = $103
  .label SCREEN = $400
  .label outer_limit = 2
.segment Code
__start: {
    // char outer_limit = 0
    lda #0
    sta.z outer_limit
    jsr main
    rts
}
// __register(A) char rec_search(__register(A) char depth)
rec_search: {
    .const OFFSET_STACK_DEPTH = 0
    .const OFFSET_STACK_RETURN_0 = 0
    // return depth;
    tsx
    lda STACK_BASE+OFFSET_STACK_DEPTH,x
    // if (depth >= outer_limit)
    cmp.z outer_limit
    bcs __breturn
  __b1:
    // depth + 1
    clc
    adc #1
    // rec_search(depth + 1)
    pha
    jsr rec_search
    // return rec_search(depth + 1);
    pla
  __breturn:
    // }
    tsx
    sta STACK_BASE+OFFSET_STACK_RETURN_0,x
    rts
}
main: {
    // caller()
    jmp caller
    // }
}
caller: {
    // outer_limit = 4
    lda #4
    sta.z outer_limit
    // char res = rec_search(1)
    lda #1
    pha
    jsr rec_search
    pla
    // if (res == 4)
    cmp #4
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
