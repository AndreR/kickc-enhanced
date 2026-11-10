lda {z2}+3
bne {la1}
lda {z1}+2
cmp {z2}+2
bcc {la1}
bne !+
lda {z1}+1
cmp {z2}+1
bcc {la1}
bne !+
lda {z1}
cmp {z2}
bcc {la1}
beq {la1}
!:
