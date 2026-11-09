ldy #{c1}
lda ({z1}),y
sec
sbc #<{c2}
sta {m1}
iny
lda ({z1}),y
sbc #>{c2}
sta {m1}+1
