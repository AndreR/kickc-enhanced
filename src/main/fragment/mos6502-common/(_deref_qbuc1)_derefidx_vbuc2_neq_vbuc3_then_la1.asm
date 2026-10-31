lda {c1}
sta $fe
lda {c1}+1
sta $ff
ldy #{c2}
lda ($fe),y
cmp #{c3}
bne {la1}
