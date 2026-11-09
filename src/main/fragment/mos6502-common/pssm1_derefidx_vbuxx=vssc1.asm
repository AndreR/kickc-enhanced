lda {m1}
sta $fe
lda {m1}+1
sta $ff
txa
tay
lda #<{c1}
sta ($fe),y
iny
lda #>{c1}
sta ($fe),y
iny
lda #<({c1}>>16)
sta ($fe),y
