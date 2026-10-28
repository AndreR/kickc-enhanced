ldy #{c1}
lda {z2}
sec
sbc ({z3}),y
sta {m1}
lda {z2}+1
sbc #0
sta {m1}+1
