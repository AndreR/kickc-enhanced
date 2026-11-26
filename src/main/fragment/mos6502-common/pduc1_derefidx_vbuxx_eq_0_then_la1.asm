lda {c1},x
bne !+
lda {c1}+1,x
bne !+
lda {c1}+2,x
bne !+
lda {c1}+3,x
beq {la1}
!: