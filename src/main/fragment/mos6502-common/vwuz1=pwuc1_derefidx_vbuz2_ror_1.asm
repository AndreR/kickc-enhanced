ldy {z2}
lda {c1}+1,y
lsr
sta {z1}+1
lda {c1},y
ror
sta {z1}
