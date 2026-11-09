lda {m2}
sta $fe
lda {m2}+1
sta $ff
ldy #{c1}
lda ($fe),y
sta {z1}
iny
lda ($fe),y
sta {z1}+1
iny
lda ($fe),y
sta {z1}+2
