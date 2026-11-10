lda {z1}+2
bne !+
lda {z1}+1
bne !+
lda {z1}
cmp #{c2}
beq {la1}
!:
