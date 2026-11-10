sec
lda {z2}
sbc {z3}
sta {m1}
lda {z2}+1
sbc {z3}+1
sta {m1}+1
lda {z2}+2
sbc #0
sta {m1}+2
lda #0
sbc #0
sta {m1}+3
