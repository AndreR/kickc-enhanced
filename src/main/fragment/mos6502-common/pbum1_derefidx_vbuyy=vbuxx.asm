lda {m1}
sta $fe
lda {m1}+1
sta $ff
stx $fd
lda $fd
sta ($fe),y
