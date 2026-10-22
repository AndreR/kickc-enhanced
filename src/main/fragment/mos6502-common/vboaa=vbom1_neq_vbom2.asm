lda {m1}
cmp {m2}
beq !+
lda #1
jmp !e+
!:
lda #0
!e:
