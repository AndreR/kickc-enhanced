lda {m1}+1
bmi {la1}
cmp {m2}+1
bcc {la1}
bne !+
lda {m2}
cmp {m1}
bcs {la1}
!:
