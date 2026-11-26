lda {m1}
sta $fe
lda {m1}+1
sta $ff
ldy {m2}
lda ($fe),y
bne {la1}
iny
lda ($fe),y
bne {la1}
iny
lda ($fe),y
bne {la1}
iny
lda ($fe),y
bne {la1}