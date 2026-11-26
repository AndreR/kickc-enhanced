lda {c1}+3,y
cmp #>{c2}>>$10
bcc !+
bne {la1}
lda {c1}+2,y
cmp #<{c2}>>$10
bcc !+
bne {la1}
lda {c1}+1,y
cmp #>{c2}
bcc !+
bne {la1}
lda {c1},y
cmp #<{c2}
bcs {la1}
!: