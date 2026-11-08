lda {c1},y
sta $fe
sty $ff
txa
tay
lda ({z1}),y
ldy $ff
cmp $fe
bne {la1}
