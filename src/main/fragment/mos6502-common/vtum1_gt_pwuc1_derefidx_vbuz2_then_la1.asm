ldy {z2}
lda {m1}+2
bne {la1}
lda {m1}+1
cmp {c1}+1,y
bcc !+
bne {la1}
lda {m1}
cmp {c1},y
beq !+
bcs {la1}
!:
