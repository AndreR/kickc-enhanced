lda {m1}+2
cmp #<({c2}>>$10)
bne !+
lda {m1}+1
cmp #>{c2}
bne !+
lda {m1}
cmp #<{c2}
beq {la1}
!:
