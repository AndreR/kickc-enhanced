lda #0
ldx #16
clc
!:
rol {m1}
rol {m1}+1
rol
cmp #{c1}
bcc !+
sbc #{c1}
!:
dex
bne !--
rol {m1}
rol {m1}+1
