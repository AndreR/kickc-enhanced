lda {c1}+2
bne !+
lda {c1}+1
cmp {c2}+1,x
bcc {la1}
bne !+
lda {c1}
cmp {c2},x
bcc {la1}
!:
