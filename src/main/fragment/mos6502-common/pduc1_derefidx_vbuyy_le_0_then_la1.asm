lda {c1},y
bne !+
lda {c1}+1,y
bne !+
lda {c1}+2,y
bne !+
lda {c1}+3,y
beq {la1}
!: