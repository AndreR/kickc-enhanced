lda {m1}
sta $fe
lda {m1}+1
sta $ff
lda {z2}
sta ($fe),y
iny
lda {z2}+1
sta ($fe),y
iny
lda {z2}+2
sta ($fe),y
