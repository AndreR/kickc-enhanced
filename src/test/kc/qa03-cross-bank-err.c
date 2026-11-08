#pragma segment_type(Bank1, ROM_BANK, 1)
#pragma segment_type(Bank2, ROM_BANK, 2)

#pragma code_seg(Bank1)
void bank1_func() {
}

#pragma code_seg(Bank2)
void bank2_func() {
    bank1_func();
}

void main() {
}
