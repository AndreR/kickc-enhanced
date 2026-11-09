lda {m1}+2
lsr
sta {m1}+3
lda {m1}+1
ror
sta {m1}+2
lda {m1}
ror
sta {m1}+1
lda #0
ror
sta {m1}
