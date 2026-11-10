lda {m2}
sta 
lda {m2}+1
sta 
ldy #0
lda (),y
sta {z1}
iny
lda (),y
sta {z1}+1
iny
lda (),y
sta {z1}+2
