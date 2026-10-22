lda {c1}+1,x
cmp #>{c2}
bcc {la1}
bne !+
lda {c1},x
cmp #<{c2}
bcc {la1}
!:
