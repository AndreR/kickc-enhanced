lda {m1}+2
bne !+
lda {m1}+1
cmp #>{c2}
bne !+
lda {m1}
cmp #<{c2}
beq {la1}
!:
