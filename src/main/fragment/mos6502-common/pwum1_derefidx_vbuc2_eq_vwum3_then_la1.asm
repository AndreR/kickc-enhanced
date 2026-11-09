lda {m1}
sta $fe
lda {m1}+1
sta $ff
ldy #{c2}
lda ($fe),y
cmp {m3}
bne !+
iny
lda ($fe),y
cmp {m3}+1
beq {la1}
!:
