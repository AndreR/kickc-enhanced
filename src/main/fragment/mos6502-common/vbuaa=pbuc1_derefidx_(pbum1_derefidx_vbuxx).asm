lda {m1}
sta $fe
lda {m1}+1
sta $ff
txa
tay
lda ($fe),y
tay
lda {c1},y
