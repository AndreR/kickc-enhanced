ldy {z2}
lda {c1},y
and {m3}
sta {z1}
lda {c1}+1,y
and {m3}+1
sta {z1}+1
