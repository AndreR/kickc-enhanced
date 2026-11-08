// Simple functions and intrinsics wrapping 6502 instructions
#include <6502.h>

// Set the processor interrupt flag - disabling IRQ's.
// Uses the SEI instruction
inline void SEI() {
    asm { sei };
}

// Clear the processor interrupt flag - enabling IRQ's.
// Uses the CLI instruction
inline void CLI() {
    asm { cli };
}

// Set processor Carry flag (SEC).
inline void SEC() {
    asm { sec };
}

// Clear processor Carry flag (CLC).
inline void CLC() {
    asm { clc };
}

// Clear processor Decimal mode flag (CLD).
inline void CLD() {
    asm { cld };
}

// Set processor Decimal mode flag (SED).
inline void SED() {
    asm { sed };
}

// No operation (NOP).
inline void NOP() {
    asm { nop };
}

inline void __builtin_sei() { asm { sei }; }
inline void __builtin_cli() { asm { cli }; }
inline void __builtin_sec() { asm { sec }; }
inline void __builtin_clc() { asm { clc }; }
inline void __builtin_cld() { asm { cld }; }
inline void __builtin_sed() { asm { sed }; }
inline void __builtin_nop() { asm { nop }; }

// Add a KickAssembler breakpoint.
// The breakpoint is compiled by KickAssembler to the output files.
// If you use VICE or C64Debugger they can automatically load the breakpoints when starting.
inline void BREAK() {
    kickasm {{ .break }};
}
