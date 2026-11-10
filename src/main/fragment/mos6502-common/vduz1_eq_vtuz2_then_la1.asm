lda {z1}+3
bne !+
lda {z1}+2
cmp {z2}+2
bne !+
lda {z1}+1
cmp {z2}+1
bne !+
lda {z1}
cmp {z2}
beq {la1}
!:
