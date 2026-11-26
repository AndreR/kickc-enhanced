lda {c1}+3,y
cmp #>{c2}>>$10
bcc {la1}
bne !+
lda {c1}+2,y
cmp #<{c2}>>$10
bcc {la1}
bne !+
lda {c1}+1,y
cmp #>{c2}
bcc {la1}
bne !+
lda {c1},y
cmp #<{c2}
bcc {la1}
!: