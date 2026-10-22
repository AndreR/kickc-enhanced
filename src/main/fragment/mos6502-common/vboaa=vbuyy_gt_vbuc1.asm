cpy #{c1}
beq !+
bcc !+
lda #1
jmp !e+
!:
lda #0
!e:
