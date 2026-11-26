lda {m1}
sta $fe
lda {m1}+1
sta $ff
ldy #{c1}+3
lda #>{c2}>>$10
cmp ($fe),y
bcc {la1}
bne !+
dey
lda #<{c2}>>$10
cmp ($fe),y
bcc {la1}
bne !+
dey
lda #>{c2}
cmp ($fe),y
bcc {la1}
bne !+
dey
lda #<{c2}
cmp ($fe),y
bcc {la1}
!: