eor #$ff
sec
adc {z1}
sta {z1}
lda {z1}+1
sbc #0
sta {z1}+1
