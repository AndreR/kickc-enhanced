lda {c1}+2
bne {la1}
lda {c1}+1
cmp {m2}+1
bcc !+
bne {la1}
lda {c1}
cmp {m2}
bcs {la1}
!:
