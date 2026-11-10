lda {m2}+2
bne !+
lda {z1}+1
cmp {m2}+1
bcc !+
bne {la1}
lda {z1}
cmp {m2}
beq !+
bcs {la1}
!:
