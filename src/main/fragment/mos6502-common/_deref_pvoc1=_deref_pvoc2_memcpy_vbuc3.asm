ldx #0
!:
lda {c2},x
sta {c1},x
inx
cpx #{c3}
bne !-
