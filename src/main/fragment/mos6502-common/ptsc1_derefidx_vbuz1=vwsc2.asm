ldy {z1}
lda #<{c2}
sta {c1},y
lda #>{c2}
sta {c1}+1,y
lda #0
sta {c1}+2,y
