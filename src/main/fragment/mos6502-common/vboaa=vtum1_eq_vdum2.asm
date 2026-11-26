lda {m2}+3
bne !f+
lda {m1}+2
cmp {m2}+2
bne !f+
lda {m1}+1
cmp {m2}+1
bne !f+
lda {m1}
cmp {m2}
bne !f+
lda #1
jmp !e+
!f:
lda #0
!e:
