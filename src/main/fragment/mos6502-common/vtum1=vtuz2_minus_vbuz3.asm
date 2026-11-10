sec
lda {z2}
sbc {z3}
sta {m1}
lda {z2}+1
sbc #0
sta {m1}+1
lda {z2}+2
sbc #0
sta {m1}+2
