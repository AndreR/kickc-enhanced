ldy #3
lda ({z1}),y
cmp #>{c1}>>$10
bcc {la1}
bne !+
dey
lda ({z1}),y
cmp #<{c1}>>$10
bcc {la1}
bne !+
dey
lda ({z1}),y
cmp #>{c1}
bcc {la1}
bne !+
dey
lda ({z1}),y
cmp #<{c1}
bcc {la1}
!: