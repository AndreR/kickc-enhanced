ldy #1
lda {m1}+1
cmp ({z2}),y
bne !+
dey
lda {m1}
cmp ({z2}),y
!:
bcs {la1}
