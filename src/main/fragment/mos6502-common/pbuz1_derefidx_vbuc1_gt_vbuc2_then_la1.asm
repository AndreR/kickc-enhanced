ldy #{c1}
lda ({z1}),y
cmp #{c2}
beq !+
bcs {la1}
!:
