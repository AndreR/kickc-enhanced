ldy {z1}
lda {c1}+1,y
cmp {m2}+1
bne !+
lda {c1},y
cmp {m2}
!:
bcs {la1}
