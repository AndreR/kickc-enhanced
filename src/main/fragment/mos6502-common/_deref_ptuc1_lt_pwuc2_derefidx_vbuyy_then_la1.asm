lda {c1}+2
bne !+
lda {c1}+1
cmp {c2}+1,y
bcc {la1}
bne !+
lda {c1}
cmp {c2},y
bcc {la1}
!:
