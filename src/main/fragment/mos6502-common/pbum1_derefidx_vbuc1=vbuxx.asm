lda {m1}
sta $fe
lda {m1}+1
sta $ff
txa
ldy #{c1}
sta ($fe),y
