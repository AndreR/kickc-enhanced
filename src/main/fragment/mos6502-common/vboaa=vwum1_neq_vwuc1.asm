lda {m1}
cmp #<{c1}
bne !a+
lda {m1}+1
cmp #>{c1}
bne !a+
lda #0
jmp !e+
!a:
lda #1
!e:
