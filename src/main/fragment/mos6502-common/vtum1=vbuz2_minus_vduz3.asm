sec
lda {z2}
sbc {z3}
sta {m1}
lda #0
sbc {z3}+1
sta {m1}+1
lda #0
sbc {z3}+2
sta {m1}+2
