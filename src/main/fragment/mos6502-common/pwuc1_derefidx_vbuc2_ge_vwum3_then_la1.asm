ldy #{c2}
lda {c1}+1,y
cmp {m3}+1
bne !+
lda {c1},y
cmp {m3}
!:
bcs {la1}
