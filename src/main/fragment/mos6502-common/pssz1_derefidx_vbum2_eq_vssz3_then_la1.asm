ldy {m2}
lda ({z1}),y
cmp {z3}
bne !+
iny
lda ({z1}),y
cmp {z3}+1
bne !+
iny
lda ({z1}),y
cmp {z3}+2
beq {la1}
!:
