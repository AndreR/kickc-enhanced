lda {z1}+3
bne {la1}
lda {z1}+2
bne {la1}
lda {z1}+1
cmp {z2}+1
bne !+
lda {z1}
cmp {z2}
!:
bcs {la1}
