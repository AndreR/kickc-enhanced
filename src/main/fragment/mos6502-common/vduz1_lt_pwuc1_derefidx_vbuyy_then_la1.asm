lda {z1}+3
bne !+
lda {z1}+2
bne !+
lda {z1}+1
cmp {c1}+1,y
bne !+
lda {z1}
cmp {c1},y
!:
bcc {la1}
