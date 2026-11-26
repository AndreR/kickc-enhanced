lda {c1}+3,x
cmp #$80
ror
sta {m1}+3
lda {c1}+2,x
ror
sta {m1}+2
lda {c1}+1,x
ror
sta {m1}+1
lda {c1},x
ror
sta {m1}