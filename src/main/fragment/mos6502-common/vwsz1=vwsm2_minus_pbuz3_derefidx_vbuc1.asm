ldy #{c1}
lda {m2}
sec
sbc ({z3}),y
sta {z1}
lda {m2}+1
sbc #0
sta {z1}+1
