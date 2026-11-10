lda {z1}+3
bne !+
lda {z1}+2
cmp {m2}+2
bcc {la1}
bne !+
lda {z1}+1
cmp {m2}+1
bcc {la1}
bne !+
lda {z1}
cmp {m2}
bcc {la1}
beq {la1}
!:
