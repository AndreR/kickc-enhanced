lda {m2}
sta $fe
lda {m2}+1
sta $ff
lda ($fe),y
sec
sbc #1
sta {m1}
