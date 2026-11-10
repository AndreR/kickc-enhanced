clc
lda {m1}
adc #<{c1}
sta {m1}
lda {m1}+1
adc #>{c1}
sta {m1}+1
lda {m1}+2
adc #<{c1}>>$10
sta {m1}+2
