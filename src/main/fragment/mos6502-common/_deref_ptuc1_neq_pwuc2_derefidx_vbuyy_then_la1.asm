lda {c1}+2
bne {la1}
lda {c1}+1
cmp {c2}+1,y
bne {la1}
lda {c1}
cmp {c2},y
bne {la1}
