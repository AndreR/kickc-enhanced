sec
lda {z1}
sbc {m2}
sta {z1}
lda {z1}+1
sbc #0
sta {z1}+1
lda {z1}+2
sbc #0
sta {z1}+2
