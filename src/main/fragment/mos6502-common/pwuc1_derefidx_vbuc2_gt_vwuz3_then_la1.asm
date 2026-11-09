ldy #{c2}
lda {z3}+1
cmp {c1}+1,y
bne !+
lda {z3}
cmp {c1},y
!:
bcc {la1}
