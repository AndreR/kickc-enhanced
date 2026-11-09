/// @file c64-disk.h
/// @brief Commodore 64 KERNAL Disk & IEC Bus I/O Suite
///
/// Provides robust disk operations using standard Commodore 64 KERNAL routines:
/// - Hardware drive probing (1541, 1571, 1581, SD2IEC, CMD) via DOS status
/// - Command channel 15 transmission (e.g. initialize, scratch, validate)
/// - Drive error/status channel reading
/// - File loading and saving with accurate KERNAL Carry error detection
///
/// NOTE: Standard KERNAL routines require the KERNAL ROM to be mapped in
/// (CPU port $01 bit 1 set, e.g. $36 or $37) and normal IEC timing.

#ifndef _C64_DISK_H
#define _C64_DISK_H

#include <c64.h>
#include <stdint.h>
#include <stdbool.h>

/// Drive types identified by disk_probe()
typedef enum : uint8_t {
    DRIVE_NONE         = 0x00, ///< No drive responded on device address
    DRIVE_1541         = 0x01, ///< Commodore 1541 (or 1541-II / 1541C / Pi1541)
    DRIVE_1571         = 0x02, ///< Commodore 1571
    DRIVE_1581         = 0x03, ///< Commodore 1581
    DRIVE_SD2IEC       = 0x04, ///< SD2IEC / IEC-ATA microcontroller drive
    DRIVE_CMD          = 0x05, ///< CMD FD-2000/4000 or CMD HD
    DRIVE_GENERIC_IEC  = 0x06  ///< Other generic IEC device
} DiskDriveType;

/// Disk operation status codes
typedef enum : uint8_t {
    DISK_OK            = 0x00, ///< Operation successful
    DISK_ERR_NO_DEVICE = 0x01, ///< Device not present or not responding
    DISK_ERR_NOT_FOUND = 0x02, ///< File not found
    DISK_ERR_READ      = 0x03, ///< Read error
    DISK_ERR_WRITE     = 0x04, ///< Write error
    DISK_ERR_ABORTED   = 0x05  ///< Operation aborted (e.g. STOP key or break)
} DiskStatus;

/// Probes the specified device address (8..11) and detects hardware drive type.
/// Sends the "UI" command to channel 15 and parses the drive signature.
/// @param device Device number (typically 8)
/// @return Detected drive type (DRIVE_NONE, DRIVE_1541, DRIVE_SD2IEC, etc.)
DiskDriveType disk_probe(uint8_t device);

/// Reads the drive status / error channel (channel 15) into buffer.
/// @param device Device number (8..11)
/// @param buffer Destination character buffer (at least 32 bytes)
/// @param max_len Maximum length of buffer
/// @return Number of bytes read
uint8_t disk_read_status(uint8_t device, char* buffer, uint8_t max_len);

/// Sends a DOS command to the drive's command channel 15.
/// @param device Device number (8..11)
/// @param cmd Null-terminated DOS command string (e.g. "I0", "S0:FILENAME")
/// @return true if command was sent successfully
bool disk_send_command(uint8_t device, const char* cmd);

/// Loads a file into memory using standard KERNAL LOAD ($FFD5).
/// @param device Device number (8..11)
/// @param filename Null-terminated filename
/// @param dest_addr Destination address in RAM (if 0, loads to file's PRG header load address)
/// @param bytes_loaded Output pointer receiving the number of bytes loaded (can be NULL)
/// @return DISK_OK on success, or error status code
DiskStatus disk_load(uint8_t device, const char* filename, void* dest_addr, uint16_t* bytes_loaded);

/// Saves a block of memory to a file using standard KERNAL SAVE ($FFD8).
/// If overwrite is true, sends "S0:<filename>" to scratch any existing file first.
/// @param device Device number (8..11)
/// @param filename Null-terminated filename
/// @param src_addr Start address of memory block
/// @param length Number of bytes to write
/// @param overwrite If true, existing file with same name is scratched first
/// @return DISK_OK on success, or error status code
DiskStatus disk_save(uint8_t device, const char* filename, const void* src_addr, uint16_t length, bool overwrite);

#endif // _C64_DISK_H
