lda #>{c2}>>$10
cmp {c1}+3,y
bcc !+
bne {la1}
lda #<{c2}>>$10
cmp {c1}+2,y
bcc !+
bne {la1}
lda #>{c2}
cmp {c1}+1,y
bcc !+
bne {la1}
lda #<{c2}
cmp {c1},y
bcs {la1}
!: