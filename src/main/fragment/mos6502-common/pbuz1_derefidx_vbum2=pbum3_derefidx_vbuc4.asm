lda {m3}
sta $fe
lda {m3}+1
sta $ff
ldy #{c4}
lda ($fe),y
ldy {m2}
sta ({z1}),y
