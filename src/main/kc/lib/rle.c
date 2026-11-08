// Ultra-Fast Run-Length Encoding (RLE) Compression Suite for 6502
#include <rle.h>

uint16_t rle_pack(const void* src, void* dest, uint16_t src_len) {
    if (src_len == 0) return 0;

    uint8_t* in = (uint8_t*)src;
    uint8_t* out = (uint8_t*)dest;

    uint16_t in_pos = 0;
    uint16_t out_pos = 0;

    while (in_pos < src_len) {
        // Check for a run of identical bytes
        uint8_t run_val = in[in_pos];
        uint8_t run_len = 1;
        while ((in_pos + run_len) < src_len && in[in_pos + run_len] == run_val && run_len < 128) {
            run_len++;
        }

        // If run is at least 3 bytes (or 2 bytes at the very end of stream), encode as repeated run
        if (run_len >= 3 || (run_len == 2 && (in_pos + 2) == src_len)) {
            out[out_pos++] = (uint8_t)(1 - (int8_t)run_len);
            out[out_pos++] = run_val;
            in_pos += run_len;
        } else {
            // Literal span: find how many non-run bytes follow
            uint16_t lit_start = in_pos;
            uint8_t lit_len = 0;

            while (in_pos < src_len && lit_len < 128) {
                // Check if a repeat run of >= 3 starts here
                if ((in_pos + 2) < src_len && in[in_pos] == in[in_pos + 1] && in[in_pos] == in[in_pos + 2]) {
                    break;
                }
                lit_len++;
                in_pos++;
            }

            if (lit_len > 0) {
                out[out_pos++] = (uint8_t)(lit_len - 1);
                for (uint8_t i = 0; i < lit_len; i++) {
                    out[out_pos++] = in[lit_start + i];
                }
            }
        }
    }

    return out_pos;
}

uint16_t rle_unpack(const void* src, uint16_t src_len, void* dest, uint16_t max_dest_len) {
    if (src_len == 0 || max_dest_len == 0) return 0;

    uint8_t* in = (uint8_t*)src;
    uint8_t* out = (uint8_t*)dest;

    uint16_t in_pos = 0;
    uint16_t out_pos = 0;

    while (in_pos < src_len && out_pos < max_dest_len) {
        int8_t header = (int8_t)in[in_pos++];

        if (header >= 0) {
            // Literal run of (header + 1) bytes
            uint8_t count = (uint8_t)header + 1;
            for (uint8_t i = 0; i < count; i++) {
                if (in_pos >= src_len || out_pos >= max_dest_len) break;
                out[out_pos++] = in[in_pos++];
            }
        } else if (header != -128) {
            // Repeated byte run of (1 - header) bytes
            uint8_t count = (uint8_t)(1 - header);
            if (in_pos >= src_len) break;
            uint8_t val = in[in_pos++];
            for (uint8_t i = 0; i < count; i++) {
                if (out_pos >= max_dest_len) break;
                out[out_pos++] = val;
            }
        }
    }

    return out_pos;
}
