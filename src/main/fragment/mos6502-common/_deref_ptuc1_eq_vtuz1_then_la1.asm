lda {c1}+2
cmp {z1}+2
bne !+
lda {c1}+1
cmp {z1}+1
bne !+
lda {c1}
cmp {z1}
beq {la1}
!:
