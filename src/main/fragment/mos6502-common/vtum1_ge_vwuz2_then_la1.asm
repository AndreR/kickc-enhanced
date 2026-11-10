lda {m1}+2
bne {la1}
lda {m1}+1
cmp {z2}+1
bcc !+
bne {la1}
lda {m1}
cmp {z2}
bcs {la1}
!:
