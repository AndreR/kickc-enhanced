lda {m1}
sta $fe
lda {m1}+1
sta $ff
ldy #{c1}+3
lda ($fe),y
cmp #>{c2}>>$10
bcc !+
bne {la1}
dey
lda ($fe),y
cmp #<{c2}>>$10
bcc !+
bne {la1}
dey
lda ($fe),y
cmp #>{c2}
bcc !+
bne {la1}
dey
lda ($fe),y
cmp #<{c2}
bcs {la1}
!: