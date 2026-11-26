lda #>{c2}>>$10
cmp {c1}+3,y
bcc {la1}
bne !+
lda #<{c2}>>$10
cmp {c1}+2,y
bcc {la1}
bne !+
lda #>{c2}
cmp {c1}+1,y
bcc {la1}
bne !+
lda #<{c2}
cmp {c1},y
bcc {la1}
!: