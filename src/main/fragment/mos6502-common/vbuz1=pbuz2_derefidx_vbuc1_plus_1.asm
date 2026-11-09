ldy #{c1}
lda ({z2}),y
clc
adc #1
sta {z1}
