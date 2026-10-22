lda {c1}+1,x
cmp #>{c2}
bcc !+
bne {la1}
lda {c1},x
cmp #<{c2}
beq !+
bcs {la1}
!:
