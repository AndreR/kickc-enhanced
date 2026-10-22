lda {c1}+1,y
cmp #>{c2}
bcc !+
bne {la1}
lda {c1},y
cmp #<{c2}
beq !+
bcs {la1}
!:
