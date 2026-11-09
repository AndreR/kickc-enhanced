ldy #{c1}
lda ({z2}),y
sec
sbc #<{c2}
sta {m1}
iny
lda ({z2}),y
sbc #>{c2}
sta {m1}+1
