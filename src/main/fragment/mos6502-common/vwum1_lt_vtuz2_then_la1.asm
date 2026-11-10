lda {z2}+2
bne {la1}
lda {m1}+1
cmp {z2}+1
bcc {la1}
bne !+
lda {m1}
cmp {z2}
bcc {la1}
!:
