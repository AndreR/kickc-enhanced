txa
tay
lda ({z1}),y
tax
iny
lda ({z1}),y
cmp {m3}+1
bne !+
txa
cmp {m3}
!:
bcc {la1}
