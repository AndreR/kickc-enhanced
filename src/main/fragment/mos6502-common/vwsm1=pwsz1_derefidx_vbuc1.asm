ldy #{c1}
lda ({z1}),y
sta {m1}
iny
lda ({z1}),y
sta {m1}+1
