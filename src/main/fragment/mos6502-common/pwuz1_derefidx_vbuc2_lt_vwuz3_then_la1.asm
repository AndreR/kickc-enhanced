ldy #{c2}
lda ({z1}),y
tax
iny
lda ({z1}),y
cmp {z3}+1
bne !+
txa
cmp {z3}
!:
bcc {la1}
