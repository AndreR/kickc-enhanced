lda {c1}+3,y
lsr
sta {m1}+3
lda {c1}+2,y
ror
sta {m1}+2
lda {c1}+1,y
ror
sta {m1}+1
lda {c1},y
ror
sta {m1}