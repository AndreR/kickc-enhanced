sec
lda {z2}
sbc {m3}
sta {m1}
lda {z2}+1
sbc #0
sta {m1}+1
lda #0
sbc #0
sta {m1}+2
