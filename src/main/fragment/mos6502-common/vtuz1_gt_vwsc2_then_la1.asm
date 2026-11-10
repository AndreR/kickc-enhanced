lda {z1}+2
bne {la1}
lda {z1}+1
cmp #>{c2}
bcc !+
bne {la1}
lda {z1}
cmp #<{c2}
beq !+
bcs {la1}
!:
