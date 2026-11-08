// Commodore 64 High-Speed Disk I/O Suite
// Portions based on Covert Bitops c64loader by Lasse Öörni (MIT License)

#include <c64-fastio.h>
#include <string.h>

uint8_t disk_read_status(uint8_t device, char* buffer, uint8_t max_len) {
    uint8_t count = 0;
    if (max_len == 0) return 0;

    uint8_t st = 0;
    asm {
        lda device
        jsr $ffb4 // TALK
        jsr $ffb7 // READST
        sta st
    }
    if ((st & 0x80) != 0) {
        asm { jsr $ffab } // UNTLK
        buffer[0] = 0;
        return 0;
    }

    asm {
        lda #$6f  // secondary address 15
        jsr $ff96 // TKSA
    }

    while (count < (max_len - 1)) {
        char ch = 0;
        asm {
            jsr $ffa5 // ACPTR
            sta ch
            jsr $ffb7 // READST
            sta st
        }
        buffer[count++] = ch;
        if (ch == 0x0d || (st & 0x40) != 0) break;
    }

    asm { jsr $ffab } // UNTLK
    buffer[count] = 0;
    return count;
}

bool disk_send_command(uint8_t device, const char* cmd) {
    uint8_t len = (uint8_t)strlen(cmd);
    uint8_t st = 0;
    asm {
        lda device
        jsr $ffb1 // LISTEN
        jsr $ffb7 // READST
        sta st
    }
    if ((st & 0x80) != 0) {
        asm { jsr $ffae } // UNLSN
        return false;
    }

    asm {
        lda #$6f  // secondary address 15
        jsr $ff93 // SECOND
    }

    for (uint8_t i = 0; i < len; i++) {
        char c = cmd[i];
        asm {
            lda c
            jsr $ffa8 // CIOUT
        }
    }

    asm { jsr $ffae } // UNLSN
    return true;
}

static bool str_contains(const char* haystack, const char* needle) {
    if (!haystack || !needle) return false;
    for (uint8_t i = 0; haystack[i] != 0; i++) {
        uint8_t j = 0;
        while (needle[j] != 0 && haystack[i + j] == needle[j]) {
            j++;
        }
        if (needle[j] == 0) return true;
    }
    return false;
}

DiskDriveType disk_probe(uint8_t device) {
    char status_buf[48];
    disk_send_command(device, "UI");
    uint8_t len = disk_read_status(device, status_buf, sizeof(status_buf));
    if (len == 0) return DRIVE_NONE;

    if (str_contains(status_buf, "1541")) return DRIVE_1541;
    if (str_contains(status_buf, "1571")) return DRIVE_1571;
    if (str_contains(status_buf, "1581")) return DRIVE_1581;
    if (str_contains(status_buf, "SD2IEC") || str_contains(status_buf, "sd2iec")) return DRIVE_SD2IEC;
    if (str_contains(status_buf, "CMD") || str_contains(status_buf, "FD")) return DRIVE_CMD;

    return DRIVE_GENERIC_IEC;
}

DiskStatus disk_fast_load(uint8_t device, const char* filename, void* dest_addr, uint16_t* bytes_loaded) {
    uint8_t fn_len = (uint8_t)strlen(filename);
    if (fn_len == 0) return DISK_ERR_NOT_FOUND;

    uint8_t sa = (dest_addr != nullptr) ? 0 : 1;

    asm {
        lda #1
        ldx device
        ldy sa
        jsr $ffba // SETLFS

        lda fn_len
        ldx filename
        ldy filename+1
        jsr $ffbd // SETNAM
    }

    void* end_ptr = nullptr;
    uint8_t err = 0;

    asm {
        lda #0 // load
        ldx dest_addr
        ldy dest_addr+1
        jsr $ffd5 // LOAD
        bcc !ok+
        sta err
        jmp !done+
    !ok:
        stx end_ptr
        sty end_ptr+1
    !done:
    }

    if (err != 0) {
        if (err == 4) return DISK_ERR_NOT_FOUND;
        return DISK_ERR_READ;
    }

    if (bytes_loaded != nullptr && dest_addr != nullptr) {
        *bytes_loaded = (uint16_t)((uintptr_t)end_ptr - (uintptr_t)dest_addr);
    }
    return DISK_OK;
}

DiskStatus disk_fast_save(uint8_t device, const char* filename, const void* src_addr, uint16_t length, bool overwrite) {
    if (overwrite) {
        char scratch_cmd[32];
        strcpy(scratch_cmd, "S0:");
        strcat(scratch_cmd, filename);
        disk_send_command(device, scratch_cmd);
    }

    uint8_t fn_len = (uint8_t)strlen(filename);
    asm {
        lda #1
        ldx device
        ldy #0
        jsr $ffba // SETLFS

        lda fn_len
        ldx filename
        ldy filename+1
        jsr $ffbd // SETNAM
    }

    uint16_t start_addr = (uint16_t)src_addr;
    uint16_t end_addr = start_addr + length;
    uint8_t err = 0;

    asm {
        lda start_addr
        sta $c1
        lda start_addr+1
        sta $c2

        lda #$c1
        ldx end_addr
        ldy end_addr+1
        jsr $ffd8 // SAVE
        bcc !ok+
        sta err
    !ok:
    }

    if (err != 0) return DISK_ERR_WRITE;
    return DISK_OK;
}
