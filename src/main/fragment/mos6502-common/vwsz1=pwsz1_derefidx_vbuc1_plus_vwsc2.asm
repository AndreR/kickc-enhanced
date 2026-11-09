ldy #{c1}
lda ({z1}),y
clc
adc #<{c2}
pha
iny
lda ({z1}),y
adc #>{c2}
sta {z1}+1
pla
sta {z1}
