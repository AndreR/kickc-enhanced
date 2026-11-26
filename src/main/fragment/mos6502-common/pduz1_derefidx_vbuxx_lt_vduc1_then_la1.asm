txa
clc
adc #3
tay
lda ({z1}),y
cmp #>{c1}>>$10
bcc {la1}
bne !+
dey
lda ({z1}),y
cmp #<{c1}>>$10
bcc {la1}
bne !+
dey
lda ({z1}),y
cmp #>{c1}
bcc {la1}
bne !+
dey
lda ({z1}),y
cmp #<{c1}
bcc {la1}
!: