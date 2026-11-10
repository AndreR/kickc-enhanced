lda {z2}+2
bne !+
lda {m1}+1
cmp {z2}+1
bne !+
lda {m1}
cmp {z2}
beq {la1}
!:
