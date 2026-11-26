ldy #3
lda #>{c1}>>$10
cmp ({z1}),y
bcc !+
bne {la1}
dey
lda #<{c1}>>$10
cmp ({z1}),y
bcc !+
bne {la1}
dey
lda #>{c1}
cmp ({z1}),y
bcc !+
bne {la1}
dey
lda #<{c1}
cmp ({z1}),y
bcs {la1}
!: