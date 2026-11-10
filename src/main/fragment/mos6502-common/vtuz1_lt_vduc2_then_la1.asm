lda #<({c2}>>$18)
bne {la1}
lda {z1}+2
cmp #<({c2}>>$10)
bcc {la1}
bne !+
lda {z1}+1
cmp #>{c2}
bcc {la1}
bne !+
lda {z1}
cmp #<{c2}
bcc {la1}
!:
