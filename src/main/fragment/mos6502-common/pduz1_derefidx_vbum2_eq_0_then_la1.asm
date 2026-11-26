ldy {m2}
lda ({z1}),y
bne !+
iny
lda ({z1}),y
bne !+
iny
lda ({z1}),y
bne !+
iny
lda ({z1}),y
beq {la1}
!: