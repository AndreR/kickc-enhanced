iny
lda ({z1}),y
cmp {m2}+1
bne !+
dey
lda ({z1}),y
cmp {m2}
!:
bcs {la1}
