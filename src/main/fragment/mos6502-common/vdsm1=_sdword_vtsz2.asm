lda {z2}
sta {m1}
lda {z2}+1
sta {m1}+1
lda {z2}+2
sta {m1}+2
ora #$7f
bmi !+
lda #0
!:
sta {m1}+3
