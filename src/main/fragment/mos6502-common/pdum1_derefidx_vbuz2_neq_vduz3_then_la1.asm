lda {m1}
sta $fe
lda {m1}+1
sta $ff
ldy {z2}
lda ($fe),y
cmp {z3}
bne {la1}
iny
lda ($fe),y
cmp {z3}+1
bne {la1}
iny
lda ($fe),y
cmp {z3}+2
bne {la1}
iny
lda ($fe),y
cmp {z3}+3
bne {la1}
