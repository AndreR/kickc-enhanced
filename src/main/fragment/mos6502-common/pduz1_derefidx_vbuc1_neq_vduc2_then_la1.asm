ldy #{c1}
lda ({z1}),y
cmp #<{c2}
bne {la1}
iny
lda ({z1}),y
cmp #>{c2}
bne {la1}
iny
lda ({z1}),y
cmp #<{c2}>>$10
bne {la1}
iny
lda ({z1}),y
cmp #>{c2}>>$10
bne {la1}