ldy #0
sec
lda {z1}
sbc ({z2}),y
sta {z1}
iny
lda {z1}+1
sbc ({z2}),y
sta {z1}+1
