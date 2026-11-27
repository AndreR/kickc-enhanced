ldy {z2}
lda {c1},y
and {z3}
sta {m1}
lda {c1}+1,y
and {z3}+1
sta {m1}+1
