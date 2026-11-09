ldy #{c4}
lda ({z3}),y
ldy {m1}
sty $fe
ldy {m1}+1
sty $ff
ldy #{c2}
sta ($fe),y
