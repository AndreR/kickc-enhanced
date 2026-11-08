lda {c1},x
sta $fe
lda {c1}+1,x
sta $ff
ldy #0
lda ($fe),y
