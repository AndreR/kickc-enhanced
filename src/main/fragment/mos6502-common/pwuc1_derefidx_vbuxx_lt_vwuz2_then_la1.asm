txa
tay
lda {c1}+1,y
cmp {z2}+1
bne !+
lda {c1},y
cmp {z2}
!:
bcc {la1}
