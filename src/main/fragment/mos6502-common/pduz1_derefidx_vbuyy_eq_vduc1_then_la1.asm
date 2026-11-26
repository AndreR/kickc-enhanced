lda ({z1}),y
cmp #<{c1}
bne !+
iny
lda ({z1}),y
cmp #>{c1}
bne !+
iny
lda ({z1}),y
cmp #<{c1}>>$10
bne !+
iny
lda ({z1}),y
cmp #>{c1}>>$10
beq {la1}
!: