ldy {m2}
lda {m3}
sta ({z1}),y
iny
lda {m3}+1
sta ({z1}),y
iny
lda {m3}+2
sta ({z1}),y
iny
lda #0
sta ({z1}),y
