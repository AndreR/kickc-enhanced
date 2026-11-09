ldy #{c2}
lda {c1},y
cmp {m3}
bne !+
lda {c1}+1,y
cmp {m3}+1
beq {la1}
!:
