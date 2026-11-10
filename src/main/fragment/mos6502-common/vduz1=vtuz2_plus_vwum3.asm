clc
lda {z2}
adc {m3}
sta {z1}
lda {z2}+1
adc {m3}+1
sta {z1}+1
lda {z2}+2
adc #0
sta {z1}+2
lda #0
adc #0
sta {z1}+3
