iny
lda ({z1}),y
cmp {m2}+1
bcc {la1}
bne !+
dey
lda ({z1}),y
cmp {m2}
bcc {la1}
beq {la1}
!:
