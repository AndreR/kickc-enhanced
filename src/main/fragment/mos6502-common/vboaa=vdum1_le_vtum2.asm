lda {m1}+3
bne !f+
lda {m2}+2
cmp {m1}+2
bne !+
lda {m2}+1
cmp {m1}+1
bne !+
lda {m2}
cmp {m1}
!:
bcs !t+
!f:
lda #0
jmp !e+
!t:
lda #1
!e:
