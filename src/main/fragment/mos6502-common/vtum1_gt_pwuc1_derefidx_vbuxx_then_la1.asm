lda {m1}+2
bne {la1}
lda {m1}+1
cmp {c1}+1,x
bcc !+
bne {la1}
lda {m1}
cmp {c1},x
beq !+
bcs {la1}
!:
