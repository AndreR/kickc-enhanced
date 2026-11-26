lda {m1}
sta $fe
lda {m1}+1
sta $ff
txa
clc
adc #3
tay
lda #>{c1}>>$10
cmp ($fe),y
bcc !+
bne {la1}
dey
lda #<{c1}>>$10
cmp ($fe),y
bcc !+
bne {la1}
dey
lda #>{c1}
cmp ($fe),y
bcc !+
bne {la1}
dey
lda #<{c1}
cmp ($fe),y
bcs {la1}
!: