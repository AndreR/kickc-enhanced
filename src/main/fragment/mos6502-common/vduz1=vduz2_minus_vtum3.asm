sec
lda {z2}
sbc {m3}
sta {z1}
lda {z2}+1
sbc {m3}+1
sta {z1}+1
lda {z2}+2
sbc {m3}+2
sta {z1}+2
lda {z2}+3
sbc #0
sta {z1}+3
