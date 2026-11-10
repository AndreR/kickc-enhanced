lda {c1}+2
cmp {z1}+2
bcc {la1}
bne !+
lda {c1}+1
cmp {z1}+1
bcc {la1}
bne !+
lda {c1}
cmp {z1}
bcc {la1}
!:
