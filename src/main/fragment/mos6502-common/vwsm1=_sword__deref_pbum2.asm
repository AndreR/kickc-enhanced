lda {m2}
sta $fe
lda {m2}+1
sta $ff
ldy #0
lda ($fe),y
sta {m1}
ora #$7f
bmi !+
lda #0
!:
sta {m1}+1
