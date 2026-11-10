clc
lda {m2}
adc #1
sta {z1}
lda {m2}+1
adc #0
sta {z1}+1
lda {m2}+2
adc #0
sta {z1}+2
