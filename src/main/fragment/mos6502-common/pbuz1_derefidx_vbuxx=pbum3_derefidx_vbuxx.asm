lda {m3}
sta $fe
lda {m3}+1
sta $ff
txa
tay
lda ($fe),y
pha
txa
tay
pla
sta ({z1}),y
