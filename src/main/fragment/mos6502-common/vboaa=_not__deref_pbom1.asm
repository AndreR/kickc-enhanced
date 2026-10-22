ldy {m1}
sty $fe
ldy {m1}+1
sty $ff
ldy #0
lda ($fe),y
eor #1
