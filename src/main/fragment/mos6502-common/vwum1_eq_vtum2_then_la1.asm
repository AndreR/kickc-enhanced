lda {m2}+2
bne !+
lda {m1}+1
cmp {m2}+1
bne !+
lda {m1}
cmp {m2}
beq {la1}
!:
