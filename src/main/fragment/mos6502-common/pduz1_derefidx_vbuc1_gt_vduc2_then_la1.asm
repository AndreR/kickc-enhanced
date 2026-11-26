ldy #{c1}+3
lda #>{c2}>>$10
cmp ({z1}),y
bcc {la1}
bne !+
dey
lda #<{c2}>>$10
cmp ({z1}),y
bcc {la1}
bne !+
dey
lda #>{c2}
cmp ({z1}),y
bcc {la1}
bne !+
dey
lda #<{c2}
cmp ({z1}),y
bcc {la1}
!: