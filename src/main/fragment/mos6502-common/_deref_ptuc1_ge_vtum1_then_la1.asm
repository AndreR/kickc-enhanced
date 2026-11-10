lda {c1}+2
cmp {m1}+2
bcc !+
bne {la1}
lda {c1}+1
cmp {m1}+1
bcc !+
bne {la1}
lda {c1}
cmp {m1}
bcs {la1}
!:
