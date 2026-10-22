lda {m2}+1
cmp {m1}+1
bcc !+
bne !f+
lda {m2}
cmp {m1}
bcc !+
!f:
lda #0
jmp !e+
!:
lda #1
!e:
