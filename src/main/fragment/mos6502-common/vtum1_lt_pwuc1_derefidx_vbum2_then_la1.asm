ldy {m2}
lda {m1}+2
bne !+
lda {m1}+1
cmp {c1}+1,y
bcc {la1}
bne !+
lda {m1}
cmp {c1},y
bcc {la1}
!:
