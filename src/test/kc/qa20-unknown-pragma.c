// QA-20: Test Unknown Pragmas Emit Warning and Do Not Crash
#pragma bogus_foo(1)
#pragma far_seg(Bank1, 1)

void main() {
    *((char*)0x0400) = 42;
}
