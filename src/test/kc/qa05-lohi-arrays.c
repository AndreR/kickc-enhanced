// Test program for __lohi split word arrays (Issue #10)

__lohi word tab[200];
__lohi word init_tab[4] = { 0x1234, 0x5678, 0x9abc, 0xdef0 };

void main() {
    char* screen = (char*)0x0400;
    
    // Check sizeof and countof
    word sz_tab = sizeof(tab);         // 400
    word cnt_tab = countof(tab);       // 200
    word sz_init = sizeof(init_tab);   // 8
    word cnt_init = countof(init_tab); // 4
    
    if (sz_tab != 400 || cnt_tab != 200 || sz_init != 8 || cnt_init != 4) {
        screen[0] = 'E'; screen[1] = '1';
        *(char*)0xd020 = 2; // red
        while(1);
    }
    
    // Check initialized array reads
    if (init_tab[0] != 0x1234 || init_tab[1] != 0x5678 || init_tab[2] != 0x9abc || init_tab[3] != 0xdef0) {
        screen[0] = 'E'; screen[1] = '2';
        *(char*)0xd020 = 2; // red
        while(1);
    }
    
    // Write and read with loop (variable index)
    for (char i = 0; i < 200; i++) {
        tab[i] = (word)i * 0x0101 + 0x0055;
    }
    
    for (char i = 0; i < 200; i++) {
        word expected = (word)i * 0x0101 + 0x0055;
        if (tab[i] != expected) {
            screen[0] = 'E'; screen[1] = '3';
            *(char*)0xd020 = 2; // red
            while(1);
        }
    }
    
    // Compound assignment
    tab[50] += 0x1000;
    if (tab[50] != ((word)50 * 0x0101 + 0x0055 + 0x1000)) {
        screen[0] = 'E'; screen[1] = '4';
        *(char*)0xd020 = 2; // red
        while(1);
    }
    
    // Pre/Post increment
    tab[100]++;
    if (tab[100] != ((word)100 * 0x0101 + 0x0055 + 1)) {
        screen[0] = 'E'; screen[1] = '5';
        *(char*)0xd020 = 2; // red
        while(1);
    }
    
    // All tests passed!
    screen[0] = 'P';
    screen[1] = 'A';
    screen[2] = 'S';
    screen[3] = 'S';
    screen[4] = '!';
    *(char*)0xd020 = 5; // green
    while(1);
}
