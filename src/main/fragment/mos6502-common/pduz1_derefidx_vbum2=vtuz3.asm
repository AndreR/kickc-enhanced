ldy {m2}
lda {z3}
sta ({z1}),y
iny
lda {z3}+1
sta ({z1}),y
iny
lda {z3}+2
sta ({z1}),y
iny
lda #0
sta ({z1}),y
