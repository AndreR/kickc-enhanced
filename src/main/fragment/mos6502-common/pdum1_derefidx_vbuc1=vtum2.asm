lda {m1}
sta $fe
lda {m1}+1
sta $ff
ldy #{c1}
lda {m2}
sta ($fe),y
iny
lda {m2}+1
sta ($fe),y
iny
lda {m2}+2
sta ($fe),y
iny
lda #0
sta ($fe),y
