sec
lda {z2}
sbc {z3}
sta {z1}
lda #0
sbc {z3}+1
sta {z1}+1
lda #0
sbc {z3}+2
sta {z1}+2
lda #0
sbc #0
sta {z1}+3
