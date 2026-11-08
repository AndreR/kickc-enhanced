// Commodore 17xx RAM Expansion Unit (REU) Driver
#include <reu.h>

uint8_t reu_is_present() {
    // Check if the REU base address register retains a written value
    uint16_t orig = REU->C64_ADDR;
    REU->C64_ADDR = 0x5aa5;
    if (REU->C64_ADDR != 0x5aa5) {
        return 0;
    }
    REU->C64_ADDR = orig;
    return 1;
}

void reu_stash(void* c64_addr, uint32_t reu_addr, uint16_t count) {
    if (count == 0) return;
    REU->C64_ADDR = (uint16_t)c64_addr;
    REU->REU_ADDR_LO = (uint16_t)reu_addr;
    REU->REU_ADDR_BANK = (uint8_t)(reu_addr >> 16);
    REU->TRANSFER_LEN = count;
    REU->ADDR_CONTROL = 0; // Both addresses increment
    REU->COMMAND = REU_CMD_EXECUTE | REU_CMD_STASH;
}

void reu_fetch(void* c64_addr, uint32_t reu_addr, uint16_t count) {
    if (count == 0) return;
    REU->C64_ADDR = (uint16_t)c64_addr;
    REU->REU_ADDR_LO = (uint16_t)reu_addr;
    REU->REU_ADDR_BANK = (uint8_t)(reu_addr >> 16);
    REU->TRANSFER_LEN = count;
    REU->ADDR_CONTROL = 0; // Both addresses increment
    REU->COMMAND = REU_CMD_EXECUTE | REU_CMD_FETCH;
}

void reu_swap(void* c64_addr, uint32_t reu_addr, uint16_t count) {
    if (count == 0) return;
    REU->C64_ADDR = (uint16_t)c64_addr;
    REU->REU_ADDR_LO = (uint16_t)reu_addr;
    REU->REU_ADDR_BANK = (uint8_t)(reu_addr >> 16);
    REU->TRANSFER_LEN = count;
    REU->ADDR_CONTROL = 0;
    REU->COMMAND = REU_CMD_EXECUTE | REU_CMD_SWAP;
}

uint8_t reu_verify(void* c64_addr, uint32_t reu_addr, uint16_t count) {
    if (count == 0) return 0;
    REU->C64_ADDR = (uint16_t)c64_addr;
    REU->REU_ADDR_LO = (uint16_t)reu_addr;
    REU->REU_ADDR_BANK = (uint8_t)(reu_addr >> 16);
    REU->TRANSFER_LEN = count;
    REU->ADDR_CONTROL = 0;
    REU->COMMAND = REU_CMD_EXECUTE | REU_CMD_VERIFY;
    return (REU->STATUS & REU_STATUS_FAULT) ? 1 : 0;
}
