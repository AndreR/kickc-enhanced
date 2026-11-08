#pragma segment_type(RomBank, ROM)
#pragma data_seg(RomBank)

char rom_var = 12;

void main() {
    rom_var = 42;
}
