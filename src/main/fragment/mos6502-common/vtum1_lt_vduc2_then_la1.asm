lda #<({c2}>>$18)
bne {la1}
lda {m1}+2
cmp #<({c2}>>$10)
bcc {la1}
bne !+
lda {m1}+1
cmp #>{c2}
bcc {la1}
bne !+
lda {m1}
cmp #<{c2}
bcc {la1}
!:
