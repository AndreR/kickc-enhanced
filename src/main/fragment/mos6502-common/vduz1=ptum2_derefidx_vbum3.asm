lda {m2}
sta $fe
lda {m2}+1
sta $ff
ldy {m3}
lda ($fe),y
sta {z1}
iny
lda ($fe),y
sta {z1}+1
iny
lda ($fe),y
sta {z1}+2
iny
lda #0
sta {z1}+3
