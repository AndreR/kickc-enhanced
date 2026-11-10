clc
lda {z1}
adc #<{c1}
sta {z1}
lda {z1}+1
adc #>{c1}
sta {z1}+1
lda {z1}+2
adc #<{c1}>>$10
sta {z1}+2
