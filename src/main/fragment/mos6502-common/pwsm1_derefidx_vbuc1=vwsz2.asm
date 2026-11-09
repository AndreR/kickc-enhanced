lda {m1}
sta $fe
lda {m1}+1
sta $ff
ldy #{c1}
lda {z2}
sta ($fe),y
iny
lda {z2}+1
sta ($fe),y
