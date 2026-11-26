lda {m1}+3
bne !f+
lda {m1}+2
cmp {m2}+2
bne !+
lda {m1}+1
cmp {m2}+1
bne !+
lda {m1}
cmp {m2}
!:
bcc !t+
!f:
lda #0
jmp !e+
!t:
lda #1
!e:
