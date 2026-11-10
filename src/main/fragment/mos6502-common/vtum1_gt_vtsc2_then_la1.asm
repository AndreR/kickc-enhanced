lda {m1}+2
cmp #<({c2}>>$10)
bcc !+
bne {la1}
lda {m1}+1
cmp #>{c2}
bcc !+
bne {la1}
lda {m1}
cmp #<{c2}
beq !+
bcs {la1}
!:
