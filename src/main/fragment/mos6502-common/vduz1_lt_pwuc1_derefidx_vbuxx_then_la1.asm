lda {z1}+3
bne !+
lda {z1}+2
bne !+
lda {z1}+1
cmp {c1}+1,x
bne !+
lda {z1}
cmp {c1},x
!:
bcc {la1}
