// Test program for __soa struct-of-arrays flattening (Issue #11)

struct Particle {
    char x;
    char y;
    char vx;
    char vy;
};

__soa struct Particle particles[32];

__soa struct Particle init_p[2] = {
    { 10, 20, 30, 40 },
    { 50, 60, 70, 80 }
};

void main() {
    char* screen = (char*)0x0400;

    // Check sizeof and countof
    word sz_part = sizeof(particles);   // 128
    word cnt_part = countof(particles); // 32
    word sz_init = sizeof(init_p);      // 8
    word cnt_init = countof(init_p);    // 2

    if (sz_part != 128 || cnt_part != 32 || sz_init != 8 || cnt_init != 2) {
        screen[0] = 'E'; screen[1] = '1';
        *(char*)0xd020 = 2; // red
        while(1);
    }

    // Check initialized array reads
    if (init_p[0].x != 10 || init_p[0].y != 20 || init_p[0].vx != 30 || init_p[0].vy != 40) {
        screen[0] = 'E'; screen[1] = '2';
        *(char*)0xd020 = 2; // red
        while(1);
    }
    if (init_p[1].x != 50 || init_p[1].y != 60 || init_p[1].vx != 70 || init_p[1].vy != 80) {
        screen[0] = 'E'; screen[1] = '3';
        *(char*)0xd020 = 2; // red
        while(1);
    }

    // Write and read with loop (variable index)
    for (char i = 0; i < 32; i++) {
        particles[i].x = i * 2;
        particles[i].y = i * 3 + 1;
        particles[i].vx = i + 10;
        particles[i].vy = 100 - i;
    }

    for (char i = 0; i < 32; i++) {
        if (particles[i].x != (char)(i * 2) ||
            particles[i].y != (char)(i * 3 + 1) ||
            particles[i].vx != (char)(i + 10) ||
            particles[i].vy != (char)(100 - i)) {
            screen[0] = 'E'; screen[1] = '4';
            *(char*)0xd020 = 2; // red
            while(1);
        }
    }

    // Compound assignment
    particles[10].y += 5;
    if (particles[10].y != (10 * 3 + 1 + 5)) {
        screen[0] = 'E'; screen[1] = '5';
        *(char*)0xd020 = 2; // red
        while(1);
    }

    // Increment
    particles[15].vx++;
    if (particles[15].vx != (15 + 10 + 1)) {
        screen[0] = 'E'; screen[1] = '6';
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
