ldy #{c1}+3
lda #>{c2}>>$10
cmp ({z1}),y
bcc !+
bne {la1}
dey
lda #<{c2}>>$10
cmp ({z1}),y
bcc !+
bne {la1}
dey
lda #>{c2}
cmp ({z1}),y
bcc !+
bne {la1}
dey
lda #<{c2}
cmp ({z1}),y
bcs {la1}
!: