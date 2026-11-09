ldy {m2}
lda ({z1}),y
cmp {m3}
bne {la1}
iny
lda ({z1}),y
cmp {m3}+1
bne {la1}
