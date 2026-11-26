ldy {z2}
lda {c1},y
asl
sta {m1}
lda {c1}+1,y
rol
sta {m1}+1
lda {c1}+2,y
rol
sta {m1}+2
lda {c1}+3,y
rol
sta {m1}+3