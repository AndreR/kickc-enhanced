lda {c1},x
cmp {z2}
bne !+
lda {c1}+1,x
cmp {z2}+1
beq {la1}
!:
