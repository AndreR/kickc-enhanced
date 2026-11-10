lda #<({c2}>>$18)
bne !+
lda {z1}+2
cmp #<({c2}>>$10)
bcc !+
bne {la1}
lda {z1}+1
cmp #>{c2}
bcc !+
bne {la1}
lda {z1}
cmp #<{c2}
bcs {la1}
!:
