lda {c1}+2
bne !+
lda {c1}+1
cmp {z2}+1
bne !+
lda {c1}
cmp {z2}
beq {la1}
!:
