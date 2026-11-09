txa
tay
lda {c1}+1,y
cmp {m2}+1
bne !+
lda {c1},y
cmp {m2}
!:
bcc {la1}
