iny
lda ({z1}),y
cmp {m2}+1
bcc !+
bne {la1}
dey
lda ({z1}),y
cmp {m2}
beq !+
bcs {la1}
!:
