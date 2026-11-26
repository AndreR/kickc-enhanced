lda {c1},x
asl
sta {m1}
lda {c1}+1,x
rol
sta {m1}+1
lda {c1}+2,x
rol
sta {m1}+2
lda {c1}+3,x
rol
sta {m1}+3
asl {m1}
rol {m1}+1
rol {m1}+2
rol {m1}+3
asl {m1}
rol {m1}+1
rol {m1}+2
rol {m1}+3