ldy #0
clc
lda ({z2}),y
adc {z3}
sta {z1}
iny
lda ({z2}),y
adc {z3}+1
sta {z1}+1
