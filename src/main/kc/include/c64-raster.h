/// @file
/// Commodore 64 Raster Beam Synchronization
#ifndef _C64_RASTER_H
#define _C64_RASTER_H

#include <c64.h>

/// Wait for a specific 9-bit raster line (0..311 on PAL, 0..262 on NTSC).
/// Correctly handles the 9th raster bit (bit 7 of $D011).
void wait_raster(unsigned int line);

/// Wait for Vertical Blanking (raster line >= 256, bottom border / off-screen area).
void wait_vblank();

/// Wait until the next frame starts (raster line 0).
inline void wait_frame() {
    wait_raster(0);
}

#endif
