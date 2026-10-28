ldy {z3}
lda {m2}
eor {c1},y
beq !+
lda #1
!:
eor #1
sta {z1}
