lda {z2}
eor {c1},x
beq !+
lda #1
!:
eor #1
sta {z1}
