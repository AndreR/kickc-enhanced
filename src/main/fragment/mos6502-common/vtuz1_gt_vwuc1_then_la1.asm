lda {z1}+2
bne {la1}
lda {z1}+1
cmp #>{c1}
bcc !+
bne {la1}
lda {z1}
cmp #<{c1}
beq !+
bcs {la1}
!:
