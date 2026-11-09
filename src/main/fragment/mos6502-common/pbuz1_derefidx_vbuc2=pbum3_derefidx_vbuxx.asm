lda {m3}
sta $fe
lda {m3}+1
sta $ff
txa
tay
lda ($fe),y
ldy #{c2}
sta ({z1}),y
