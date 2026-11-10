sec
lda {m1}
sbc {z2}
sta {m1}
lda {m1}+1
sbc {z2}+1
sta {m1}+1
lda {m1}+2
sbc {z2}+2
sta {m1}+2
lda {m1}+3
sbc #0
sta {m1}+3
