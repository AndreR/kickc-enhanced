ldy #{c1}
lda ({z2}),y
clc
adc #<{c2}
sta {z1}
iny
lda ({z2}),y
adc #>{c2}
sta {z1}+1
