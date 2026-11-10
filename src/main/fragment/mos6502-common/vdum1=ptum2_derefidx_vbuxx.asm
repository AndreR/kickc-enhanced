lda {m2}
sta $fe
lda {m2}+1
sta $ff
txa
tay
lda ($fe),y
sta {m1}
iny
lda ($fe),y
sta {m1}+1
iny
lda ($fe),y
sta {m1}+2
iny
lda #0
sta {m1}+3
