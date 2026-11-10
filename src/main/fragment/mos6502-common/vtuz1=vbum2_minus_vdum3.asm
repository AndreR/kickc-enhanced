sec
lda {m2}
sbc {m3}
sta {z1}
lda #0
sbc {m3}+1
sta {z1}+1
lda #0
sbc {m3}+2
sta {z1}+2
