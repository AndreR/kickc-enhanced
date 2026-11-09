lda {m2}
sta $fe
lda {m2}+1
sta $ff
lda ($fe),y
sta {m1}
