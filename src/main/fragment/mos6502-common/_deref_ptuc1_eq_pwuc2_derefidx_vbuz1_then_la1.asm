ldy {z1}
lda {c1}+2
bne !+
lda {c1}+1
cmp {c2}+1,y
bne !+
lda {c1}
cmp {c2},y
beq {la1}
!:
