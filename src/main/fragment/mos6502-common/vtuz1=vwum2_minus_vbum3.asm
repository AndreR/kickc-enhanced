sec
lda {m2}
sbc {m3}
sta {z1}
lda {m2}+1
sbc #0
sta {z1}+1
lda #0
sbc #0
sta {z1}+2
