lda {m2}
sta $fe
lda {m2}+1
sta $ff
ldy #{c1}
lda ($fe),y
sta {m1}
