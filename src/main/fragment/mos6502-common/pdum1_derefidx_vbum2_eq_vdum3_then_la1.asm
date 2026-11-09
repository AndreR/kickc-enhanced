lda {m1}
sta $fe
lda {m1}+1
sta $ff
ldy {m2}
lda ($fe),y
cmp {m3}
bne !+
iny
lda ($fe),y
cmp {m3}+1
bne !+
iny
lda ($fe),y
cmp {m3}+2
bne !+
iny
lda ($fe),y
cmp {m3}+3
beq {la1}
!:
