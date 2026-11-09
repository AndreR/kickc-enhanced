ldy #{c1}
lda #<{c2}
sta ({z1}),y
iny
lda #>{c2}
sta ({z1}),y
iny
lda #<({c2}>>16)
sta ({z1}),y
iny
lda #>({c2}>>16)
sta ({z1}),y
