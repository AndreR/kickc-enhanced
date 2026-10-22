lda {z2}
sta {z1}
ora #$7f
bmi !+
lda #0
!:
sta {z1}+1
