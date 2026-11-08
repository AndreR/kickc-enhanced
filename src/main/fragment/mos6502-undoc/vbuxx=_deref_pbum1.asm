lda {m1}
sta $fe
lda {m1}+1
sta $ff
ldy #0
lax ($fe),y
