lda {m1}+1
cmp {m2}+1
bne !+
lda {m1}
cmp {m2}
!:
bcs !+
lda #0
jmp !e+
!:
lda #1
!e:
