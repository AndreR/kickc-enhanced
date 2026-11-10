lda #<({c1}>>$18)
bne !+
lda {m1}+2
cmp #<({c1}>>$10)
bne !+
lda {m1}+1
cmp #>{c1}
bne !+
lda {m1}
cmp #<{c1}
beq {la1}
!:
