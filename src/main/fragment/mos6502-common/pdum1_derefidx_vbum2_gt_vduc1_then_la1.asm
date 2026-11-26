lda {m1}
sta $fe
lda {m1}+1
sta $ff
ldy {m2}
iny
iny
iny
lda #>{c1}>>$10
cmp ($fe),y
bcc {la1}
bne !+
dey
lda #<{c1}>>$10
cmp ($fe),y
bcc {la1}
bne !+
dey
lda #>{c1}
cmp ($fe),y
bcc {la1}
bne !+
dey
lda #<{c1}
cmp ($fe),y
bcc {la1}
!: