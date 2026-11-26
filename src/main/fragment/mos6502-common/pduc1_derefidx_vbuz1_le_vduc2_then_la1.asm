ldx {z1}
lda #>{c2}>>$10
cmp {c1}+3,x
bcc !+
bne {la1}
lda #<{c2}>>$10
cmp {c1}+2,x
bcc !+
bne {la1}
lda #>{c2}
cmp {c1}+1,x
bcc !+
bne {la1}
lda #<{c2}
cmp {c1},x
bcs {la1}
!: