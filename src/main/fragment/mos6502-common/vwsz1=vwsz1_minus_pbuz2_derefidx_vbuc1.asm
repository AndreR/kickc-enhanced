ldy #{c1}
lda {z1}
sec
sbc ({z2}),y
sta {z1}
lda {z1}+1
sbc #0
sta {z1}+1
