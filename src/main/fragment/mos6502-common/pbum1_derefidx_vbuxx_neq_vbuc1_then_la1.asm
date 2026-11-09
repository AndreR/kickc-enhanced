lda {m1}
sta $fe
lda {m1}+1
sta $ff
txa
tay
lda ($fe),y
cmp #{c1}
bne {la1}
