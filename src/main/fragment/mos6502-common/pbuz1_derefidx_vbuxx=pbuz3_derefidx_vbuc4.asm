ldy #{c4}
lda ({z3}),y
pha
txa
tay
pla
sta ({z1}),y
