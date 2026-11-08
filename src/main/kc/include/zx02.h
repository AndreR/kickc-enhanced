/// @file zx02.h
/// @brief Universal ZX02 Decompressor for 6502 (Einar Saukas format)
///
/// Decompresses data directly from Cartridge-ROM ($8000-$9FFF) or RAM
/// into any destination ($0400-$FFFF, including high-RAM under Kernal).

#ifndef _ZX02_H
#define _ZX02_H

/// @brief Decompresses ZX02 compressed data into destination buffer.
/// @param source Pointer to compressed data stream (ROM or RAM).
/// @param destination Pointer to destination buffer in RAM.
void zx02_decompress(void* source, void* destination);

#endif
