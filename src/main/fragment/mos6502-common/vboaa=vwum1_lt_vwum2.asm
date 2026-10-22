lda {m1}+1
cmp {m2}+1
bcc !+
bne !f+
lda {m1}
cmp {m2}
bcc !+
!f:
lda #0
jmp !e+
!:
lda #1
!e:
