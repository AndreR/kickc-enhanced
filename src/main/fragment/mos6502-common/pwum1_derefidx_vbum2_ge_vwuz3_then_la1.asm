lda {m1}
sta $fe
lda {m1}+1
sta $ff
ldy {m2}
lda ($fe),y
tax
iny
lda ($fe),y
cmp {z3}+1
bne !+
txa
cmp {z3}
!:
bcs {la1}
