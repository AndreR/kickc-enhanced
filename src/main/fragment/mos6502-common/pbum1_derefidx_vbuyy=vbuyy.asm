lda {m1}
sta $fe
lda {m1}+1
sta $ff
sty $fd
lda $fd
sta ($fe),y
