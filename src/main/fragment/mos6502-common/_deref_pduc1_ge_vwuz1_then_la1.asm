lda {c1}+3
bne {la1}
lda {c1}+2
bne {la1}
lda {c1}+1
cmp {z1}+1
bcc !+
bne {la1}
lda {c1}
cmp {z1}
bcs {la1}
!:
