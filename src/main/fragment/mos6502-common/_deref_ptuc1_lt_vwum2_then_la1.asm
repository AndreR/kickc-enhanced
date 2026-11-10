lda {c1}+2
bne !+
lda {c1}+1
cmp {m2}+1
bcc {la1}
bne !+
lda {c1}
cmp {m2}
bcc {la1}
!:
