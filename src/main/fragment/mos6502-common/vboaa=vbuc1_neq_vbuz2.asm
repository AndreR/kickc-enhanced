lda #{c1}
cmp {z2}
bne !+
lda #0
jmp !e+
!:
lda #1
!e:
