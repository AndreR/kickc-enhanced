lda {m1}
sta $fe
lda {m1}+1
sta $ff
ldy {z2}
lda {z3}
sta ($fe),y
iny
lda {z3}+1
sta ($fe),y
iny
lda {z3}+2
sta ($fe),y
