sec
lda {z1}
sbc {m2}
sta {z1}
lda {z1}+1
sbc {m2}+1
sta {z1}+1
lda {z1}+2
sbc {m2}+2
sta {z1}+2
lda {z1}+3
sbc #0
sta {z1}+3
