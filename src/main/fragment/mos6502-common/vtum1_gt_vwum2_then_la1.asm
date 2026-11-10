lda {m1}+2
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
