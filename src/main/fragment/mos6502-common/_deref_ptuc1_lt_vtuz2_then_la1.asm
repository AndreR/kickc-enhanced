lda {c1}+2
cmp {z2}+2
bcc {la1}
bne !+
lda {c1}+1
cmp {z2}+1
bcc {la1}
bne !+
lda {c1}
cmp {z2}
bcc {la1}
!:
