lda #<({c1}>>$18)
bne !+
lda {z1}+2
cmp #<({c1}>>$10)
bcc !+
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
