lda #>{c2}>>$10
cmp {c1}+3,x
bcc {la1}
bne !+
lda #<{c2}>>$10
cmp {c1}+2,x
bcc {la1}
bne !+
lda #>{c2}
cmp {c1}+1,x
bcc {la1}
bne !+
lda #<{c2}
cmp {c1},x
bcc {la1}
!: