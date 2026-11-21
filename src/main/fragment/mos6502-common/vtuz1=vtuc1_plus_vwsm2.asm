lda {m2}
clc
adc #<{c1}
sta {z1}
lda {m2}+1
adc #>{c1}
sta {z1}+1
lda {m2}+1
ora #$7f
bmi !+
lda #0
!:
adc #<{c1}>>$10
sta {z1}+2
