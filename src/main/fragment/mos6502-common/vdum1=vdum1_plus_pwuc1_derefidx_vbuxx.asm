clc
lda {m1}
adc {c1},x
sta {m1}
lda {m1}+1
adc {c1}+1,x
sta {m1}+1
bcc !+
inc {m1}+2
bne !+
inc {m1}+3
!:
