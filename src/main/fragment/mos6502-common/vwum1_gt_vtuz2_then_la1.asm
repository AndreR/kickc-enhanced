lda {z2}+2
bne !+
lda {m1}+1
cmp {z2}+1
bcc !+
bne {la1}
lda {m1}
cmp {z2}
beq !+
bcs {la1}
!:
