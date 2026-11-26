lda {m2}+3
bne !t+
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
lda #0
jmp !e+
!t:
lda #1
!e:
