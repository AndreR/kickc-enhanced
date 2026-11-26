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
asl {m1}
rol {m1}+1
rol {m1}+2
rol {m1}+3