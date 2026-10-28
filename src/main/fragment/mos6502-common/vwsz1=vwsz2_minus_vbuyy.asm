tya
eor #$ff
sec
adc {z2}
sta {z1}
lda {z2}+1
sbc #0
sta {z1}+1
