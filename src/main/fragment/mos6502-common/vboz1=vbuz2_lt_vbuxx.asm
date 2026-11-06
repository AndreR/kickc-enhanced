stx $fe
lda {z2}
cmp $fe
lda #0
rol
eor #1
sta {z1}
