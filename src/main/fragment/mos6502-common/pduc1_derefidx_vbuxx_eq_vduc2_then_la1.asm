lda {c1},x
cmp #<{c2}
bne !+
lda {c1}+1,x
cmp #>{c2}
bne !+
lda {c1}+2,x
cmp #<{c2}>>$10
bne !+
lda {c1}+3,x
cmp #>{c2}>>$10
beq {la1}
!: