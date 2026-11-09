lda {m3}
sta $fe
lda {m3}+1
sta $ff
lda ($fe),y
ldy {z2}
sta ({z1}),y
