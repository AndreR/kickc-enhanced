stx $fe
txa
ora #$7f
bmi !+
lda #0
!:
sta $ff
lda #<{c1}
sec
sbc $fe
sta {m1}
lda #>{c1}
sbc $ff
sta {m1}+1
