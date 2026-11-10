lda {m2}+2
bne !+
lda {m1}+1
cmp {m2}+1
bcc !+
bne {la1}
lda {m1}
cmp {m2}
bcs {la1}
!:
