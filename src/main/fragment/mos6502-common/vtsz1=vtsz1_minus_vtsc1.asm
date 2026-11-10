sec
lda {z1}
sbc #<{c1}
sta {z1}
lda {z1}+1
sbc #>{c1}
sta {z1}+1
lda {z1}+2
sbc #<{c1}>>$10
sta {z1}+2
