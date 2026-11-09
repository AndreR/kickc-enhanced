ldy #0
clc
lda {z1}
adc ({z2}),y
sta {z1}
iny
lda {z1}+1
adc ({z2}),y
sta {z1}+1
