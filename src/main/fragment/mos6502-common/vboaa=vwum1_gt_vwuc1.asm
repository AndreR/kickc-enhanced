lda #<{c1}
cmp {m1}
lda #>{c1}
sbc {m1}+1
bcc !+
lda #1
jmp !e+
!:
lda #0
!e:
