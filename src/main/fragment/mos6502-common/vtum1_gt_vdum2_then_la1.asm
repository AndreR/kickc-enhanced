lda {m2}+3
bne !+
lda {m1}+2
cmp {m2}+2
bcc !+
bne {la1}
lda {m1}+1
cmp {m2}+1
bcc !+
bne {la1}
lda {m1}
cmp {m2}
beq !+
bcs {la1}
!:
