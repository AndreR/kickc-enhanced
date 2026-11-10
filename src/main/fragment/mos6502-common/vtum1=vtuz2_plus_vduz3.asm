clc
lda {z2}
adc {z3}
sta {m1}
lda {z2}+1
adc {z3}+1
sta {m1}+1
lda {z2}+2
adc {z3}+2
sta {m1}+2
