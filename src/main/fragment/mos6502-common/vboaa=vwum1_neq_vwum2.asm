lda {m1}
cmp {m2}
bne !+
lda {m1}+1
cmp {m2}+1
beq !f+
!:
lda #1
jmp !e+
!f:
lda #0
!e:
