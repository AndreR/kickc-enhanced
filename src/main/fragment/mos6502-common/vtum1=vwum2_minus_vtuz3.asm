sec
lda {m2}
sbc {z3}
sta {m1}
lda {m2}+1
sbc {z3}+1
sta {m1}+1
lda #0
sbc {z3}+2
sta {m1}+2
