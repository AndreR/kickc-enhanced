ldy {m2}
iny
iny
iny
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