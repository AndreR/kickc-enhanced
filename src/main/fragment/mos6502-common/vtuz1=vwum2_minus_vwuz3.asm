sec
lda {m2}
sbc {z3}
sta {z1}
lda {m2}+1
sbc {z3}+1
sta {z1}+1
lda #0
sbc #0
sta {z1}+2
