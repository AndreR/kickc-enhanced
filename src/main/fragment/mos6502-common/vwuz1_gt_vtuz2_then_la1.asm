lda {z2}+2
bne !+
lda {z1}+1
cmp {z2}+1
bcc !+
bne {la1}
lda {z1}
cmp {z2}
beq !+
bcs {la1}
!:
