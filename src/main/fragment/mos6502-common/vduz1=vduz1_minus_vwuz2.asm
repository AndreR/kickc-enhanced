sec
lda {z1}
sbc {z2}
sta {z1}
lda {z1}+1
sbc {z2}+1
sta {z1}+1
bcs !+
dec {z1}+2
lda {z1}+2
cmp #$ff
bne !+
dec {z1}+3
!:
