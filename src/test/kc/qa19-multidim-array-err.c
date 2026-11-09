// QA-19: Test Multidimensional Array Unsupported Compile Error
// Multidimensional arrays (char arr[4][4]) are unsupported without -Warraytype.

void main() {
    char baa[4][4];
    baa[0][2] = 1;
    *((char*)0x0400) = baa[0][2];
}
