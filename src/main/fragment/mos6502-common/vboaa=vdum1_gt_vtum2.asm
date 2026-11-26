lda {m1}+3
bne !t+
lda {m2}+2
cmp {m1}+2
bne !+
lda {m2}+1
cmp {m1}+1
bne !+
lda {m2}
cmp {m1}
!:
bcc !t+
lda #0
jmp !e+
!t:
lda #1
!e:
