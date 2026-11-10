sec
lda {m2}
sbc {m3}
sta {m1}
lda #0
sbc {m3}+1
sta {m1}+1
lda #0
sbc {m3}+2
sta {m1}+2
