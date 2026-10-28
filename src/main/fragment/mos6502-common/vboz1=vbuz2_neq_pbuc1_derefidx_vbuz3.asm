ldy {z3}
lda {z2}
eor {c1},y
beq !+
lda #1
!:
sta {z1}
