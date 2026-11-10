clc
lda {z1}
adc {m2}
sta {z1}
lda {z1}+1
adc {m2}+1
sta {z1}+1
lda {z1}+2
adc {m2}+2
sta {z1}+2
