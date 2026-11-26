ldx {m1}
lda {c1}+3,x
cmp #>{c2}>>$10
bcc !+
bne {la1}
lda {c1}+2,x
cmp #<{c2}>>$10
bcc !+
bne {la1}
lda {c1}+1,x
cmp #>{c2}
bcc !+
bne {la1}
lda {c1},x
cmp #<{c2}
bcs {la1}
!: