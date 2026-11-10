lda {m1}
sta 
lda {m1}+1
sta 
ldy #0
lda {z2}
sta (),y
iny
lda {z2}+1
sta (),y
iny
lda {z2}+2
sta (),y
