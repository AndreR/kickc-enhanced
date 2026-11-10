lda {m1}
sta $fe
lda {m1}+1
sta $ff
ldy {m2}
lda {m3}
sta ($fe),y
iny
lda {m3}+1
sta ($fe),y
iny
lda {m3}+2
sta ($fe),y
iny
lda #0
sta ($fe),y
