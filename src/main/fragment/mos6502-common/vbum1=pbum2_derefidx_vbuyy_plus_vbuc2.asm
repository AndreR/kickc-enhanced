lda {m2}
sta $fe
lda {m2}+1
sta $ff
lda ($fe),y
clc
adc #{c2}
sta {m1}
