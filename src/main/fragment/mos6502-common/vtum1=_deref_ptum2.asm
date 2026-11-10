lda {m2}
sta 
lda {m2}+1
sta 
ldy #0
lda (),y
sta {m1}
iny
lda (),y
sta {m1}+1
iny
lda (),y
sta {m1}+2
