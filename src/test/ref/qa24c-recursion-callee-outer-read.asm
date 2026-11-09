// QA-24c: Outer variable read/written by a procedure called from a recursive procedure
// Verifies that transitive callees in the re-entrant call tree have their outer variables
// converted to LOAD_STORE so that caller does not synthesize uninitialized phi-parameters.
  // Commodore 64 PRG executable file
.file [name="qa24c-recursion-callee-outer-read.prg", type="prg", segments="Program"]
.segmentdef Program [segments="Basic, Code, Data"]
.segmentdef Basic [start=$0801]
.segmentdef Code [start=$80d]
.segmentdef Data [startAfter="Code"]
.segmentdef StackRam [start=$0100, min=$0100, max=$01af]
.segment Basic
:BasicUpstart(__start)
  .const STACK_BASE = $103
  .label SCREEN = $400
  .label outer_bound = 3
  .label outer_counter = 2
.segment Code
__start: {
    // char outer_bound = 0
    lda #0
    sta.z outer_bound
    // char outer_counter = 0
    sta.z outer_counter
    jsr main
    rts
}
// __register(X) char rec_tree(__register(X) char depth)
rec_tree: {
    .const OFFSET_STACK_DEPTH = 0
    .const OFFSET_STACK_RETURN_0 = 0
    // return depth;
    tsx
    lda STACK_BASE+OFFSET_STACK_DEPTH,x
    tax
    // check_bound(depth)
    jsr check_bound
    // if (check_bound(depth))
    cmp #0
    bne __breturn
  __b1:
    // depth + 1
    inx
    txa
    // rec_tree(depth + 1)
    pha
    jsr rec_tree
    // return rec_tree(depth + 1);
    pla
    tax
  __breturn:
    // }
    txa
    tsx
    sta STACK_BASE+OFFSET_STACK_RETURN_0,x
    rts
}
main: {
    // caller()
    jmp caller
    // }
}
// __register(A) char check_bound(__register(X) char val)
check_bound: {
    // if (val >= outer_bound)
    cpx.z outer_bound
    bcc __b1
    lda #1
    rts
  __b1:
    // outer_counter++;
    inc.z outer_counter
    lda #0
    // }
    rts
}
caller: {
    // outer_bound = 5
    lda #5
    sta.z outer_bound
    // outer_counter = 0
    lda #0
    sta.z outer_counter
    // char res = rec_tree(1)
    lda #1
    pha
    jsr rec_tree
    pla
    // if (res == 5 && outer_counter == 4)
    // depth goes 1, 2, 3, 4, 5: check_bound called for 1 (counter=1), 2 (counter=2), 3 (counter=3), 4 (counter=4), 5 (bound met, counter=4)
    cmp #5
    bne __b2
    lda #4
    cmp.z outer_counter
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
