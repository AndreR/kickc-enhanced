clc
lda {z2}
adc {z3}
sta {m1}
lda {z2}+1
adc #0
sta {m1}+1
lda {z2}+2
adc #0
sta {m1}+2
