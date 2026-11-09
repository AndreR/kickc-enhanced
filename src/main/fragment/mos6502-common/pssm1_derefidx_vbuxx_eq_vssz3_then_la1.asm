lda {m1}
sta $fe
lda {m1}+1
sta $ff
txa
tay
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
beq {la1}
!:
