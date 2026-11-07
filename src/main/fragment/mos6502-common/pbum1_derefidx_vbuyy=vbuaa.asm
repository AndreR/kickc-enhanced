pha
lda {m1}
sta $fe
lda {m1}+1
sta $ff
pla
sta ($fe),y
