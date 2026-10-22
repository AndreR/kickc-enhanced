ldy #0
lda ({z1}),y
clc
adc #<{c1}
sta ({z1}),y
iny
lda ({z1}),y
adc #>{c1}
sta ({z1}),y
