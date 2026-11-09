ldy {z1}
lda {m2}+1
cmp {c1}+1,y
bne !+
lda {m2}
cmp {c1},y
!:
bcc {la1}
