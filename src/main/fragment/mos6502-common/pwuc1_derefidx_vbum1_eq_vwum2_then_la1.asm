ldy {m1}
lda {c1},y
cmp {m2}
bne !+
lda {c1}+1,y
cmp {m2}+1
beq {la1}
!:
