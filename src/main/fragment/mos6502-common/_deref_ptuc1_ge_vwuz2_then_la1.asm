lda {c1}+2
bne {la1}
lda {c1}+1
cmp {z2}+1
bcc !+
bne {la1}
lda {c1}
cmp {z2}
bcs {la1}
!:
