lda {m1}
cmp #<{c1}
bne !+
lda {m1}+1
cmp #>{c1}
beq !a+
!:
lda #0
jmp !e+
!a:
lda #1
!e:
