lda {m1}
sta $fe
lda {m1}+1
sta $ff
tya
ldy #{c1}
sta ($fe),y
