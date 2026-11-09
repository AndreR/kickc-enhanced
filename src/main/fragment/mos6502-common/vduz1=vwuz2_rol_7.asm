lda {z2}+1
lsr
sta {z1}+2
lda {z2}
ror
sta {z1}+1
lda #0
ror
sta {z1}
lda #0
sta {z1}+3
