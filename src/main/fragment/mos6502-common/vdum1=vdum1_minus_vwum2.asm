sec
lda {m1}
sbc {m2}
sta {m1}
lda {m1}+1
sbc {m2}+1
sta {m1}+1
bcs !+
dec {m1}+2
lda {m1}+2
cmp #$ff
bne !+
dec {m1}+3
!:
