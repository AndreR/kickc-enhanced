lda {m1}
sta $fe
lda {m1}+1
sta $ff
ldy #{c1}
lda #<{c2}
sta ($fe),y
iny
lda #>{c2}
sta ($fe),y
iny
lda #<({c2}>>16)
sta ($fe),y
iny
lda #>({c2}>>16)
sta ($fe),y
