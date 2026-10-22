lda {m2}+1
cmp {m1}+1
bne !+
lda {m2}
cmp {m1}
!:
bcs !+
lda #0
jmp !e+
!:
lda #1
!e:
