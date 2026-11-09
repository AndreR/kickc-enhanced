lda {m3}
sta $fe
lda {m3}+1
sta $ff
ldy #{c4}
lda ($fe),y
ldy {m1}
sty $fe
ldy {m1}+1
sty $ff
pha
txa
tay
pla
sta ($fe),y
