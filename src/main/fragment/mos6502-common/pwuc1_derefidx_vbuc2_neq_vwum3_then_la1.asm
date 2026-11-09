ldy #{c2}
lda {c1},y
cmp {m3}
bne {la1}
lda {c1}+1,y
cmp {m3}+1
bne {la1}
