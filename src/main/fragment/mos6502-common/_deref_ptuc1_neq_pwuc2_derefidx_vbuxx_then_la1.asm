lda {c1}+2
bne {la1}
lda {c1}+1
cmp {c2}+1,x
bne {la1}
lda {c1}
cmp {c2},x
bne {la1}
