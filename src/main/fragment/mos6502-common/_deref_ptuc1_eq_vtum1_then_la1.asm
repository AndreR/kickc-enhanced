lda {c1}+2
cmp {m1}+2
bne !+
lda {c1}+1
cmp {m1}+1
bne !+
lda {c1}
cmp {m1}
beq {la1}
!:
