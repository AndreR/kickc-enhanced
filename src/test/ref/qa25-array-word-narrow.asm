// QA-25: Direct indexed addressing for static arrays <= 256 bytes indexed by word expressions
// Verifies that expressions indexing an array fitting in <= 256 bytes do not synthesize
// an unnecessary 16-bit Zero-Page pointer.
  // Commodore 64 PRG executable file
.file [name="qa25-array-word-narrow.prg", type="prg", segments="Program"]
.segmentdef Program [segments="Basic, Code, Data"]
.segmentdef Basic [start=$0801]
.segmentdef Code [start=$80d]
.segmentdef Data [startAfter="Code"]
.segmentdef StackRam [start=$0100, min=$0100, max=$01af]
.segment Basic
:BasicUpstart(main)
  .label SCREEN = $400
.segment Code
main: {
    .label __3 = 4
    .label sum = 8
    .label i = 6
    lda #<0
    sta.z sum
    sta.z sum+1
    sta.z i
    sta.z i+1
  __b2:
    // for(unsigned int i = 0; i < 4; i++)
    lda.z i+1
    bne !+
    lda.z i
    cmp #4
    bcc __b3
  !:
    // if(sum == 160)
    // score_table[0]=10 + score_table[2]=30 + score_table[4]=50 + score_table[6]=70 = 160
    lda.z sum+1
    bne !+
    lda.z sum
    cmp #$a0
    beq __b1
  !:
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
  __b3:
    // get_score(i * 2)
    lda.z i
    asl
    sta.z get_score.idx
    lda.z i+1
    rol
    sta.z get_score.idx+1
    jsr get_score
    // sum += get_score(i * 2)
    clc
    lda.z sum
    adc.z __3
    sta.z sum
    lda.z sum+1
    adc.z __3+1
    sta.z sum+1
    // for(unsigned int i = 0; i < 4; i++)
    inc.z i
    bne !+
    inc.z i+1
  !:
    jmp __b2
}
// __zp(4) unsigned int get_score(__zp(2) unsigned int idx)
get_score: {
    .label __0 = 2
    .label return = 4
    .label idx = 2
    // return score_table[idx];
    sta.z 2
    stx.z 2+1
    asl.z __0
    rol.z __0+1
    ldx.z __0
    lda score_table,x
    sta.z return
    lda score_table+1,x
    sta.z return+1
    // }
    tax
    lda.z 4
    rts
}
.segment Data
  score_table: .word $a, $14, $1e, $28, $32, $3c, $46, $50, $5a, $64, $6e, $78, $82, $8c, $96, $a0
