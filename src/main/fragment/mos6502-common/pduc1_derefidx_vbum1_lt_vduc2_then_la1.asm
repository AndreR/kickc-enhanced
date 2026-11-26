ldx {m1}
lda {c1}+3,x
cmp #>{c2}>>$10
bcc {la1}
bne !+
lda {c1}+2,x
cmp #<{c2}>>$10
bcc {la1}
bne !+
lda {c1}+1,x
cmp #>{c2}
bcc {la1}
bne !+
lda {c1},x
cmp #<{c2}
bcc {la1}
!: