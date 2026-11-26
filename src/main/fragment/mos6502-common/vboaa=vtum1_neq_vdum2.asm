lda {m2}+3
bne !t+
lda {m1}+2
cmp {m2}+2
bne !t+
lda {m1}+1
cmp {m2}+1
bne !t+
lda {m1}
cmp {m2}
bne !t+
lda #0
jmp !e+
!t:
lda #1
!e:
