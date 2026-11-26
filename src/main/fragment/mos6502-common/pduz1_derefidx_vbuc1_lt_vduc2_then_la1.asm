ldy #{c1}+3
lda ({z1}),y
cmp #>{c2}>>$10
bcc {la1}
bne !+
dey
lda ({z1}),y
cmp #<{c2}>>$10
bcc {la1}
bne !+
dey
lda ({z1}),y
cmp #>{c2}
bcc {la1}
bne !+
dey
lda ({z1}),y
cmp #<{c2}
bcc {la1}
!: