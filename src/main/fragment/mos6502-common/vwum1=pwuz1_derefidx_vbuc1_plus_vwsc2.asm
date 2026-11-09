ldy #{c1}
lda ({z1}),y
clc
adc #<{c2}
sta {m1}
iny
lda ({z1}),y
adc #>{c2}
sta {m1}+1
