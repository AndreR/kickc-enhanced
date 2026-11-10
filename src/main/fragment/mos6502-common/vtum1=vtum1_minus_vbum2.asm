sec
lda {m1}
sbc {m2}
sta {m1}
lda {m1}+1
sbc #0
sta {m1}+1
lda {m1}+2
sbc #0
sta {m1}+2
