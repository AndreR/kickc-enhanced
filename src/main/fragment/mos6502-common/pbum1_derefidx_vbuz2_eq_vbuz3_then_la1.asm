lda {m1}
sta $fe
lda {m1}+1
sta $ff
ldy {z2}
lda ($fe),y
cmp {z3}
beq {la1}
