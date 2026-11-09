ldy #{c1}
lda ({z1}),y
cmp #{c2}
bcc {la1}
beq {la1}
