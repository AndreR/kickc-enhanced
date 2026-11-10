clc
lda {m2}
adc {m3}
sta {z1}
lda #0
adc {m3}+1
sta {z1}+1
lda #0
adc #0
sta {z1}+2
