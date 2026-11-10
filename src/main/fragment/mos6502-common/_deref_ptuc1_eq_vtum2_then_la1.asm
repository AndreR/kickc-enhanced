lda {c1}+2
cmp {m2}+2
bne !+
lda {c1}+1
cmp {m2}+1
bne !+
lda {c1}
cmp {m2}
beq {la1}
!:
