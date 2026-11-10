lda {m1}+2
bne {la1}
lda {m1}+1
cmp #>{c2}
bne {la1}
lda {m1}
cmp #<{c2}
bne {la1}
