lda {m1}
sta 
lda {m1}+1
sta 
ldy #0
lda {m2}
sta (),y
iny
lda {m2}+1
sta (),y
iny
lda {m2}+2
sta (),y
