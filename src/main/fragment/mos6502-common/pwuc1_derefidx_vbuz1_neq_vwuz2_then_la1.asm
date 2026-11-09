ldy {z1}
lda {c1},y
cmp {z2}
bne {la1}
lda {c1}+1,y
cmp {z2}+1
bne {la1}
