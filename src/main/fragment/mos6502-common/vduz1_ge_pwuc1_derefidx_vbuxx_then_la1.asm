lda {z1}+3
bne {la1}
lda {z1}+2
bne {la1}
lda {z1}+1
cmp {c1}+1,x
bne !+
lda {z1}
cmp {c1},x
!:
bcs {la1}
