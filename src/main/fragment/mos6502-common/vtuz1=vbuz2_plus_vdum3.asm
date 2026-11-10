clc
lda {z2}
adc {m3}
sta {z1}
lda #0
adc {m3}+1
sta {z1}+1
lda #0
adc {m3}+2
sta {z1}+2
