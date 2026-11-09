txa
tay
lda ({z3}),y
ldy {m1}
sty $fe
ldy {m1}+1
sty $ff
pha
txa
tay
pla
sta ($fe),y
