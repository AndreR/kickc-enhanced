/// @file 
/// Simple functions and intrinsics wrapping 6502 instructions
#ifndef _6502_H
#define _6502_H

/// Set the processor interrupt flag - disabling IRQ's.
/// Uses the SEI instruction
inline void SEI();

/// Clear the processor interrupt flag - enabling IRQ's.
/// Uses the CLI instruction
inline void CLI();

/// Set processor Carry flag (SEC).
inline void SEC();

/// Clear processor Carry flag (CLC).
inline void CLC();

/// Clear processor Decimal mode flag (CLD).
inline void CLD();

/// Set processor Decimal mode flag (SED).
inline void SED();

/// No operation (NOP).
inline void NOP();

/// Compiler builtin aliases (lower-case / GNU-style)
inline void __builtin_sei();
inline void __builtin_cli();
inline void __builtin_sec();
inline void __builtin_clc();
inline void __builtin_cld();
inline void __builtin_sed();
inline void __builtin_nop();

/// Test bit 7 of a memory location (MSB / sign bit).
#define __builtin_bit7(addr) (*((signed char*)(addr)) < 0)

/// Test bit 6 of a memory location.
#define __builtin_bit6(addr) ((*((char*)(addr)) & 0x40) != 0)

/// Add a KickAssembler breakpoint.
/// The breakpoint is compiled by KickAssembler to the output files.
/// If you use VICE or C64Debugger they can automatically load the breakpoints when starting.
inline void BREAK();


/// Cycle-exact delay for N clock cycles (compile-time constant >= 2, or 0 for no-op).
/// Synthesizes optimal register-preserving instruction sequence (NOP, BIT $00).
__intrinsic void delay_cycles(const unsigned long cycles);
__intrinsic void __builtin_delay_cycles(const unsigned long cycles);

#endif
