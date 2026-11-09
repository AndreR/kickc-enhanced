lda {m1}+3
bne !+
lda {m1}+2
bne !+
lda {m1}+1
cmp {m2}+1
bne !+
lda {m1}
cmp {m2}
!:
bcc {la1}
