lda {m2}+1
cmp {m1}+1
bcc {la1}
bne !+
lda {m2}
cmp {m1}
bcc {la1}
!:
