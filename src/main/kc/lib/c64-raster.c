// Commodore 64 Raster Beam Synchronization
#include <c64-raster.h>

void wait_raster(unsigned int line) {
    char r_lo = BYTE0(line);
    char r_hi = (BYTE1(line) & 1) ? 0x80 : 0x00;

    // If currently on this line, wait until beam moves away
    while ((VICII->RASTER == r_lo) && ((VICII->CONTROL1 & 0x80) == r_hi));

    // Wait until raster beam arrives at line
    while ((VICII->RASTER != r_lo) || ((VICII->CONTROL1 & 0x80) != r_hi));
}

void wait_vblank() {
    // Wait until raster line >= 256 (bit 7 of $D011 is 1)
    while ((VICII->CONTROL1 & 0x80) == 0);
}
