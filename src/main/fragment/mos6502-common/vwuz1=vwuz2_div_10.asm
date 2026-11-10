lda {z2}
sta {z1}
lda {z2}+1
sta {z1}+1
lda #0
ldx #16
clc
!:
rol {z1}
rol {z1}+1
rol
cmp #10
bcc !+
sbc #10
!:
dex
bne !--
rol {z1}
rol {z1}+1
