sty $ff
lda {m2}
clc
adc $ff
sta {m1}
lda {m2}+1
adc #0
sta {m1}+1
