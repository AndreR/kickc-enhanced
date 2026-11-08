tya
asl
tay
clc
lda {z1}
adc {c1},y
sta {z1}
lda {z1}+1
adc {c1}+1,y
sta {z1}+1
bcc !+
inc {z1}+2
bne !+
inc {z1}+3
!:
