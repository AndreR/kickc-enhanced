ldy #{c1}
lda ({z1}),y
cmp #<{c2}
bne !+
iny
lda ({z1}),y
cmp #>{c2}
bne !+
iny
lda ({z1}),y
cmp #<{c2}>>$10
bne !+
iny
lda ({z1}),y
cmp #>{c2}>>$10
beq {la1}
!: