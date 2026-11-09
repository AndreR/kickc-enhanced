ldy {z2}
lda {z1}+3
bne {la1}
lda {z1}+2
bne {la1}
lda {z1}+1
cmp {c1}+1,y
bne !+
lda {z1}
cmp {c1},y
!:
bcs {la1}
