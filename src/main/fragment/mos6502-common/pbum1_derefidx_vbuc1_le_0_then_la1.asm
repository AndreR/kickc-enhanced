lda {m1}
sta $fe
lda {m1}+1
sta $ff
ldy #{c1}
lda ($fe),y
beq {la1}
