ldy {m1}
sty 
ldy {m1}+1
sty 
ldy #0
lda (),y
eor #1
sta {m1}
