lda {m1}+2
cmp #<({c2}>>$10)
bcc {la1}
bne !+
lda {m1}+1
cmp #>{c2}
bcc {la1}
bne !+
lda {m1}
cmp #<{c2}
bcc {la1}
beq {la1}
!:
