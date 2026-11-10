lda {z1}+2
bne !+
lda {z1}+1
cmp {m2}+1
bcc {la1}
bne !+
lda {z1}
cmp {m2}
bcc {la1}
!:
