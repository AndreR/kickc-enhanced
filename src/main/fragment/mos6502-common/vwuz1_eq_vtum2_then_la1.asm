lda {m2}+2
bne !+
lda {z1}+1
cmp {m2}+1
bne !+
lda {z1}
cmp {m2}
beq {la1}
!:
