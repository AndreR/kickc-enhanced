lda {m1}
sta $fe
lda {m1}+1
sta $ff
lax ($fe),y
