lda {z1}
cmp {m2}
bne !+
lda {z1}+1
cmp {m2}+1
bne !+
lda {z1}+2
cmp {m2}+2
beq {la1}
!:
