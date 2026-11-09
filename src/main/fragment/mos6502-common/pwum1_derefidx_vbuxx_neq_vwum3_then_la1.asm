lda {m1}
sta $fe
lda {m1}+1
sta $ff
txa
tay
lda ($fe),y
cmp {m3}
bne {la1}
iny
lda ($fe),y
cmp {m3}+1
bne {la1}
