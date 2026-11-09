lda {m1}
sta $fe
lda {m1}+1
sta $ff
ldy #{c2}
lda ($fe),y
cmp {z3}
bne !+
iny
lda ($fe),y
cmp {z3}+1
bne !+
iny
lda ($fe),y
cmp {z3}+2
bne !+
iny
lda ($fe),y
cmp {z3}+3
beq {la1}
!:
