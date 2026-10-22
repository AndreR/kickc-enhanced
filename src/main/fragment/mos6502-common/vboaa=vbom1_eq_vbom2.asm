lda {m1}
cmp {m2}
bne !+
lda #1
jmp !e+
!:
lda #0
!e:
