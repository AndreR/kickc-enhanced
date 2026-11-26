lda {m1}
sta $fe
lda {m1}+1
sta $ff
ldy {m2}
iny
iny
iny
lda ($fe),y
cmp #>{c1}>>$10
bcc {la1}
bne !+
dey
lda ($fe),y
cmp #<{c1}>>$10
bcc {la1}
bne !+
dey
lda ($fe),y
cmp #>{c1}
bcc {la1}
bne !+
dey
lda ($fe),y
cmp #<{c1}
bcc {la1}
!: