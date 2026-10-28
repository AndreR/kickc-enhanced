ldy #{c1}
lda ({z2}),y
sta {z1}
lda #0
sta {z1}+1
