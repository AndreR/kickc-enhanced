lda {m1}
cmp #<{c1}
lda {m1}+1
sbc #>{c1}
bcc !+
lda #1
jmp !e+
!:
lda #0
!e:
