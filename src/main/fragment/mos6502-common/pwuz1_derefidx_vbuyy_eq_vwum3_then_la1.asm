lda ({z1}),y
cmp {m3}
bne !+
iny
lda ({z1}),y
cmp {m3}+1
beq {la1}
!:
