/// @file
/// Commodore 17xx RAM Expansion Unit (REU) Driver
/// Supports 1700 (128 KB), 1750 (512 KB), 1764 (256 KB) and Ultimate-REU.
#ifndef _REU_H
#define _REU_H

#include <stdint.h>

struct REU_REGISTERS {
    volatile uint8_t STATUS;          // $DF00: Status register
    volatile uint8_t COMMAND;         // $DF01: Command register
    volatile uint16_t C64_ADDR;       // $DF02-$DF03: C64 base address
    volatile uint16_t REU_ADDR_LO;    // $DF04-$DF05: REU base address (lo/mid)
    volatile uint8_t REU_ADDR_BANK;   // $DF06: REU bank (0..7 / 0..255)
    volatile uint16_t TRANSFER_LEN;   // $DF07-$DF08: Number of bytes to transfer
    volatile uint8_t IRQ_MASK;        // $DF09: Interrupt mask register
    volatile uint8_t ADDR_CONTROL;    // $DF0A: Address control register
};

#define REU ((struct REU_REGISTERS*)0xdf00)

// REU Command Register Flags
#define REU_CMD_EXECUTE      0x80
#define REU_CMD_AUTOLOAD     0x20
#define REU_CMD_STASH        0x00   // C64 -> REU
#define REU_CMD_FETCH        0x01   // REU -> C64
#define REU_CMD_SWAP         0x02   // Swap C64 <-> REU
#define REU_CMD_VERIFY       0x03   // Compare C64 <-> REU

// REU Status Register Flags
#define REU_STATUS_INTERRUPT 0x80
#define REU_STATUS_END       0x40
#define REU_STATUS_FAULT     0x20
#define REU_STATUS_SIZE_512K 0x10

/// Detect if an REU is present at $DF00
uint8_t reu_is_present();

/// Transfer data from C64 RAM to REU (Stash)
void reu_stash(void* c64_addr, uint32_t reu_addr, uint16_t count);

/// Transfer data from REU to C64 RAM (Fetch)
void reu_fetch(void* c64_addr, uint32_t reu_addr, uint16_t count);

/// Swap data between C64 RAM and REU
void reu_swap(void* c64_addr, uint32_t reu_addr, uint16_t count);

/// Compare data between C64 RAM and REU (returns 0 on match, non-zero on mismatch)
uint8_t reu_verify(void* c64_addr, uint32_t reu_addr, uint16_t count);

#endif
