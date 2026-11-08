// Test C2Y / N3355 Named Loops in KickC Enhanced

void main() {
    char* const screen = (char*)0x0400;

    // Test 1: break outer from inner loop
    char count1 = 0;
    outer_loop: for(char y = 0; y < 10; y++) {
        for(char x = 0; x < 10; x++) {
            count1++;
            if(y == 2 && x == 3) {
                break outer_loop; // Should break completely out of outer_loop
            }
        }
    }
    // y=0: 10 times, y=1: 10 times, y=2: 4 times (x=0,1,2,3) -> count1 == 24
    screen[0] = count1;

    // Test 2: continue outer from inner loop
    char count2 = 0;
    loop_y: for(char y = 0; y < 5; y++) {
        for(char x = 0; x < 5; x++) {
            count2++;
            if(x == 1) {
                continue loop_y; // Skips the rest of x-loop and advances y-loop
            }
        }
    }
    // For each y (5 times), x=0 and x=1 run -> 2 * 5 = 10 times -> count2 == 10
    screen[1] = count2;

    // Test 3: break outer loop from inside a switch!
    char count3 = 0;
    outer_switch_test: for(char i = 0; i < 10; i++) {
        switch(i) {
            case 0:
            case 1:
                count3++;
                break; // Breaks out of switch only
            case 2:
                count3 += 10;
                break outer_switch_test; // Breaks out of the outer for-loop!
            default:
                count3 += 50;
                break;
        }
    }
    // i=0: count3=1, i=1: count3=2, i=2: count3=12, then breaks outer! -> count3 == 12
    screen[2] = count3;

    // Test 4: while loop with named break
    char count4 = 0;
    w_outer: while(count4 < 20) {
        while(1) {
            count4 += 5;
            if(count4 >= 15) {
                break w_outer;
            }
            break; // breaks inner while
        }
    }
    // iter 1: count4=5, breaks inner, w_outer continues
    // iter 2: count4=10, breaks inner, w_outer continues
    // iter 3: count4=15, breaks w_outer! -> count4 == 15
    screen[3] = count4;
}
