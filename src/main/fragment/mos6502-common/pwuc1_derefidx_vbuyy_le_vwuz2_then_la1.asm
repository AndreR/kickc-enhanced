lda {z2}+1
cmp {c1}+1,y
bne !+
lda {z2}
cmp {c1},y
!:
bcs {la1}
