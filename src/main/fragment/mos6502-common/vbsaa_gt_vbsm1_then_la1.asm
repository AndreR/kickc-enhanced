sec
sbc {m1}
beq !+
bvc !f+
eor #$80
!f:
bpl {la1}
!:
