clc
lda {m1}
adc {z2}
sta {m1}
lda {m1}+1
adc {z2}+1
sta {m1}+1
lda {m1}+2
adc {z2}+2
sta {m1}+2
