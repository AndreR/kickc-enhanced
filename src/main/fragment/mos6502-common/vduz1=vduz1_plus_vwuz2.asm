clc
lda {z1}
adc {z2}
sta {z1}
lda {z1}+1
adc {z2}+1
sta {z1}+1
bcc !+
inc {z1}+2
bne !+
inc {z1}+3
!:
