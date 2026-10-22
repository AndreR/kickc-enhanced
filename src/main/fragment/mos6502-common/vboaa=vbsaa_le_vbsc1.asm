sec
sbc #{c1}
beq !a+
bvc !+
eor #$80
!:
asl
lda #0
rol
eor #1
jmp !e+
!a:
lda #1
!e:
