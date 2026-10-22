ldy #0
lda ({z2}),y
sta {z1}
ora #$7f
bmi !+
lda #0
!:
sta {z1}+1
