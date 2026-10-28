ldy #{c1}
lda {m1}
sec
sbc ({z2}),y
sta {m1}
lda {m1}+1
sbc #0
sta {m1}+1
