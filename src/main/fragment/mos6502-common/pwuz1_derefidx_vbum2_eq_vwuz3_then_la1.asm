ldy {m2}
lda ({z1}),y
cmp {z3}
bne !+
iny
lda ({z1}),y
cmp {z3}+1
beq {la1}
!:
