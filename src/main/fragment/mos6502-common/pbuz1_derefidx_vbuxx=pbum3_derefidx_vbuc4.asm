lda {m3}
sta $fe
lda {m3}+1
sta $ff
ldy #{c4}
lda ($fe),y
pha
txa
tay
pla
sta ({z1}),y
