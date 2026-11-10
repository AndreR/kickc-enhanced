lda #0
ldx #16
clc
!:
rol {z1}
rol {z1}+1
rol
cmp #{c1}
bcc !+
sbc #{c1}
!:
dex
bne !--
rol {z1}
rol {z1}+1
