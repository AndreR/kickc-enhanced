lda {m1}
sta $fe
lda {m1}+1
sta $ff
ldy {m2}
lda ($fe),y
bne !+
iny
lda ($fe),y
bne !+
iny
lda ($fe),y
bne !+
iny
lda ($fe),y
beq {la1}
!: