lda {z1}+2
bne {la1}
lda {z1}+1
cmp {m2}+1
bcc !+
bne {la1}
lda {z1}
cmp {m2}
bcs {la1}
!:
