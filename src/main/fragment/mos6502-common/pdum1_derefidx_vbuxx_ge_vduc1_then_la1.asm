lda {m1}
sta $fe
lda {m1}+1
sta $ff
txa
clc
adc #3
tay
lda ($fe),y
cmp #>{c1}>>$10
bcc !+
bne {la1}
dey
lda ($fe),y
cmp #<{c1}>>$10
bcc !+
bne {la1}
dey
lda ($fe),y
cmp #>{c1}
bcc !+
bne {la1}
dey
lda ($fe),y
cmp #<{c1}
bcs {la1}
!: