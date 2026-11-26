lda {m1}
sta $fe
lda {m1}+1
sta $ff
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