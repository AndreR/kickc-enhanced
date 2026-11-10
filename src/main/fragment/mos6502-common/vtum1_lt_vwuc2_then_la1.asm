lda {m1}+2
bne !+
lda {m1}+1
cmp #>{c2}
bcc {la1}
bne !+
lda {m1}
cmp #<{c2}
bcc {la1}
!:
