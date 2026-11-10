lda {z1}+2
bne {la1}
lda {z1}+1
cmp {c1}+1,x
bcc !+
bne {la1}
lda {z1}
cmp {c1},x
beq !+
bcs {la1}
!:
