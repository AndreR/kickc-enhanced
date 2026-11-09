ldy {m2}
lda ({z1}),y
cmp {m3}
bne !+
iny
lda ({z1}),y
cmp {m3}+1
bne !+
iny
lda ({z1}),y
cmp {m3}+2
bne !+
iny
lda ({z1}),y
cmp {m3}+3
beq {la1}
!:
