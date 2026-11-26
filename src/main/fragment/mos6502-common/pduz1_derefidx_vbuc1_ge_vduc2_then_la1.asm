ldy #{c1}+3
lda ({z1}),y
cmp #>{c2}>>$10
bcc !+
bne {la1}
dey
lda ({z1}),y
cmp #<{c2}>>$10
bcc !+
bne {la1}
dey
lda ({z1}),y
cmp #>{c2}
bcc !+
bne {la1}
dey
lda ({z1}),y
cmp #<{c2}
bcs {la1}
!: