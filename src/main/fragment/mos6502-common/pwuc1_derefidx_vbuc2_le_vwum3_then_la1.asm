ldy #{c2}
lda {m3}+1
cmp {c1}+1,y
bne !+
lda {m3}
cmp {c1},y
!:
bcs {la1}
