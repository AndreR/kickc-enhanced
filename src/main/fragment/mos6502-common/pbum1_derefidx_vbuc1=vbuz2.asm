lda {m1}
sta $fe
lda {m1}+1
sta $ff
lda {z2}
ldy #{c1}
sta ($fe),y
