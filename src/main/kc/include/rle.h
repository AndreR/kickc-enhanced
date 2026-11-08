/// @file rle.h
/// @brief Ultra-Fast Run-Length Encoding (RLE) Compression Suite for 6502
///
/// Symmetric real-time PackBits RLE compression and decompression.
/// Zero dynamic memory allocation, bounded worst-case expansion (< +1%).

#ifndef _RLE_H
#define _RLE_H

#include <stdint.h>
#include <stdbool.h>

/// Compresses a buffer using standard PackBits byte-run encoding.
/// @param src Pointer to uncompressed source data.
/// @param dest Pointer to destination buffer (must be at least src_len + (src_len / 128) + 1 bytes).
/// @param src_len Number of uncompressed bytes to compress.
/// @return Number of compressed bytes written to dest.
uint16_t rle_pack(const void* src, void* dest, uint16_t src_len);

/// Decompresses PackBits RLE data into destination buffer.
/// @param src Pointer to compressed data.
/// @param src_len Length of compressed data in bytes.
/// @param dest Pointer to destination buffer in RAM.
/// @param max_dest_len Maximum number of bytes to write into dest buffer.
/// @return Number of decompressed bytes written to dest.
uint16_t rle_unpack(const void* src, uint16_t src_len, void* dest, uint16_t max_dest_len);

#endif
