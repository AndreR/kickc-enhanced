lda {m2}
cmp {m1}
lda {m2}+1
sbc {m1}+1
lda {m2}+2
sbc {m1}+2
lda {m2}+3
sbc {m1}+3
bvc !+
eor #$80
!:
bpl {la1}
