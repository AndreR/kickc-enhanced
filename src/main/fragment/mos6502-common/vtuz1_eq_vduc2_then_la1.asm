lda #<({c2}>>$18)
bne !+
lda {z1}+2
cmp #<({c2}>>$10)
bne !+
lda {z1}+1
cmp #>{c2}
bne !+
lda {z1}
cmp #<{c2}
beq {la1}
!:
