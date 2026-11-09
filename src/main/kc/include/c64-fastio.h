/// @file c64-fastio.h
/// @brief Covert Bitops High-Speed Disk I/O Suite for Commodore 64
/// Based on Covert Bitops Loadersystem V3.x by Lasse Öörni (MIT License).
///
/// Features:
/// - Custom 2-bit fastloader protocol for 1541, 1571, 1581, CMD FD/HD, SD2IEC (ELoad)
/// - Transparent operation with Kernal disabled ($01 = $35)
/// - Rapid asset loading with IRQs active
///
/// Note: Filenames in Covert Bitops are indexed by 2-character hexadecimal numbers (0x00..0xFF, e.g. "00", "01").

#ifndef _C64_FASTIO_H
#define _C64_FASTIO_H

#include <stdint.h>
#include <stdbool.h>

/// Loader operation mode set by fastio_init()
typedef enum : uint8_t {
    FASTIO_MODE_KERNAL   = 0x00, ///< Stock Kernal protocol (no IRQs allowed)
    FASTIO_MODE_IDE64    = 0x01, ///< Kernal protocol with IRQs allowed (IDE64 / fast drive emulation)
    FASTIO_MODE_FASTLOAD = 0xFF  ///< High-speed 2-bit protocol active
} FastIOMode;

/// Initializes the fastloader system. Detects drive and uploads fastloader code.
/// Disables timer IRQ, sets CPU port to $35 (Kernal off).
/// @param force_safe_mode If true, forces stock Kernal mode instead of uploading drive code.
/// @return Active loader mode (FASTIO_MODE_FASTLOAD, FASTIO_MODE_IDE64, FASTIO_MODE_KERNAL).
FastIOMode fastio_init(bool force_safe_mode);

/// Opens a file by file number (0x00..0xFF, corresponding to disk filenames "00".."FF").
/// @param filenumber Hexadecimal file number (e.g. 0x00 for file "00").
/// @return true if opened successfully, false on error.
bool fastio_open(uint8_t filenumber);

/// Reads a byte from the currently open file.
/// @return Read byte, or 0 if EOF.
uint8_t fastio_get_byte(void);

/// Checks whether a file is currently open.
/// @return true if open, false if closed or EOF.
bool fastio_is_open(void);

/// Loads an unpacked file to the load address specified in its 2-byte header.
/// @param filenumber Hexadecimal file number (0x00..0xFF).
/// @return true on success, false on error.
bool fastio_load(uint8_t filenumber);

/// Loads an unpacked file to a specified raw memory destination (ignoring header).
/// @param filenumber Hexadecimal file number (0x00..0xFF).
/// @param dest Destination memory pointer.
/// @return true on success, false on error.
bool fastio_load_raw(uint8_t filenumber, void* dest);

/// Saves a memory block to a preallocated file on disk using fast drivecode.
/// @param filenumber Hexadecimal file number (0x00..0xFF).
/// @param src Start address in RAM.
/// @param length Number of bytes to save.
/// @return true on success, false on error.
bool fastio_save(uint8_t filenumber, const void* src, uint16_t length);

/// Sets the safe raster range for 2-bit fastloading when sprites are active.
/// @param top_y Y-coordinate of topmost sprite.
/// @param bottom_y Y-coordinate of bottommost sprite.
void fastio_set_sprite_range(uint8_t top_y, uint8_t bottom_y);

/// Disables sprite raster limits and allows 2-bit transfer anywhere on screen.
void fastio_set_no_sprites(void);

#endif // _C64_FASTIO_H
