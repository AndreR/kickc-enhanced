sec
lda {m2}
sbc {m3}
sta {z1}
lda {m2}+1
sbc {m3}+1
sta {z1}+1
lda {m2}+2
sbc {m3}+2
sta {z1}+2
lda #0
sbc #0
sta {z1}+3
