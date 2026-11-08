# KickC Enhanced – Compiler Architecture & Feature Roadmap

KickC Enhanced is an optimizing C-to-6502 cross-compiler designed to generate readable, ultra-compact, and cycle-efficient machine code for MOS 6502-family systems (such as the Commodore 64).

This document outlines the compiler architecture, completed milestones, planned low-level 6502 optimizations, and our selective modernization track incorporating high-leverage features from **C11**, **C23**, and the upcoming **C2Y (C29)** ISO standards.

---

## 1. Architectural Philosophy

1. **Zero-Overhead 6502 Code Generation:**  
   Unlike traditional desktop C compilers that rely on heavy software stack frames and dynamic call conventions, KickC operates on a **static call graph**, global **SSA live-range analysis**, and aggressive **Zero-Page register coalescing**.
2. **No Unwanted Integer Promotion:**  
   Standard C mandates that 8-bit operands in expressions are promoted to `int` (16- or 32-bit). On a 1 MHz 8-bit processor, this degrades throughput by a factor of 2x to 4x. KickC defaults to strict 8-bit arithmetic unless explicitly cast or required by types.
3. **Hardware-Aware Language Extensions:**  
   Direct segment typings, inline assembly fragments, memory-mapped I/O safety, and cycle-exact intrinsics are treated as first-class citizens.
4. **Selective Modern C Adoption:**  
   We do not aim for 100% compliance with desktop ISO C (which includes threads, atomics, complex numbers, and dynamic heap overhead). Instead, we adopt **compile-time, zero-runtime-cost syntactic and structural features** from modern standards that solve acute problems in 8-bit embedded and retro systems.

---

## 2. Feature Matrix (Strictly Sequenced F-00 to F-36)

| ID | Category | Feature Description | Origin / Standard | Status |
| :---: | :--- | :--- | :---: | :---: |
| **F-00** | Architecture | Segment Type Classification (`#pragma segment_type`) & Cross-Bank Verification | KickC Ext | ✅ Completed |
| **F-01** | Codegen | Split Jump-Tables for dense `switch` via 12-cycle RTS dispatch | 6502 Idiom | ✅ Completed |
| **F-02** | Types | C11 Anonymous Unions & 2–4 Byte Assignment Loop-Unrolling | C11 | ⚪ Planned |
| **F-03** | Intrinsics | Atomic Status Intrinsics (`__builtin_bit7/6`, `SEC`, `CLC`, etc.) | KickC Ext | ✅ Completed |
| **F-04** | Codegen | Zero-Syntax Auto Pointer-to-SMC & Dynamic Call Dispatch (RAM) | 6502 Idiom | ⚪ Planned |
| **F-05** | Assembler | Zero-Waste Conditional Page-Boundary Alignment (`.align $100`) | KickAss Ext | ✅ Completed |
| **F-06** | Allocator | Inter-Segment Zero-Page Coalescing across disjoint data segments | KickC Core | ✅ Completed |
| **F-07** | Controlflow | Count-Down Loop-Inversion (`BPL`) & `__countdown` directive | 6502 Idiom | ✅ Completed |
| **F-08** | Numerics | Bit-exact Quarter-Square Table Multiplication (`<multiply.h>`) | 6502 ROM | ✅ Completed |
| **F-09** | Arrays | 8-Bit Indexing for $\le 128$ Word-Arrays & `__lohi` Split Arrays | 6502 Idiom | ⚪ Planned |
| **F-10** | Data Layout | Struct-of-Arrays (`__soa`) Automatic Flattening | Data Layout | ⚪ Planned |
| **F-11** | ABI | 16-Bit Hardware Register Pair `AX` for Return Values & Params | Calling Conv | ⚪ Planned |
| **F-12** | Peephole | Pass-5 Flag Tracking (N/Z-preservation across stores & status ops) | Pass 5 | ✅ Completed |
| **F-13** | Arithmetics | Reciprocal Constant Division & Modulo (`/ 10`, `% 10`) | Pass 2 SSA | ✅ Completed |
| **F-14** | Interprocedural | Read-Only Zero-Page Parameter Preservation across `JSR` | Pass 5 | ✅ Completed |
| **F-15** | Codegen | Page-Aligned 16-Bit Pointer Addition Folding (`#<$xx00`) | Pass 5 | ✅ Completed |
| **F-16** | Arithmetics | Compact 8x8->16 Multiplication without 16-bit Zero-Extension | Pass 2 SSA | ✅ Completed |
| **F-17** | Preprocessor | `#embed` Directive for Direct Binary Asset Embedding | C23 | ⚪ Planned |
| **F-18** | Types | Typed 8-Bit Enumerations (`enum : uint8_t`) | C23 | ✅ Completed |
| **F-19** | Operators | Compile-Time Array Length Operator `countof()` | C2Y (C29) | ✅ Completed |
| **F-20** | Controlflow | Named Loops: `break label;` & `continue label;` | C2Y (C29) | ✅ Completed |
| **F-21** | Safety | Compile-Time Assertions (`static_assert` / `_Static_assert`) | C11 / C23 | ✅ Completed |
| **F-22** | Metaprog | `_Generic` Type-Dispatch for Polymorphic 8/16-Bit Macros | C11 | ✅ Completed |
| **F-23** | Lexer | Digit Separators (`0b1100_0011`, `1_000`) & C23 Keywords (`nullptr`, `{}`) | C23 | ✅ Completed |
| **F-24** | Controlflow | `if`- & `switch`-Statements with Initializers | C2Y (C29) | ✅ Completed |
| **F-25** | ABI / Opt | Auto Carry-Flag Boolean Returns (`__carry` / Auto-Carry ABI) | 6502 Idiom | ✅ Completed |
| **F-26** | ABI / Opt | Leaf-Function Automatic Register Parameter Passing (A, X, Y) | Calling Conv | ⚪ Planned |
| **F-27** | Types | 8.8 Fixed-Point Data Type (`fixed8_8`) & 0-Cycle Shift Fusing | ISO TR 18037 | ⚪ Planned |
| **F-28** | Numerics | BCD Decimal Accumulator Arithmetic (`__bcd` / `SED` Support) | 6502 Hardware | ⚪ Planned |
| **F-29** | Memory | Hardware Stack-RAM Segment (`$0100–$01AF` Scratchpad Buffer) | C64 Architecture | ✅ Completed |
| **F-30** | Timing | Cycle-Exact Delay Synthesizer (`delay_cycles(N)` in `<6502.h>`) | Hardware Timing | ✅ Completed |
| **F-31** | Codegen | Pre-Indexed Zero-Page Addressing Optimization (`LDA ($zp,X)`) | 6502 Hardware | ⚪ Planned |
| **F-32** | Generator | Compile-Time Table Generator (`__builtin_lut` / Constexpr Arrays) | Tooling | ⚪ Planned |
| **F-33** | Types | ISO-C Bitfields in Structs (`uint8_t a:1, b:1;` for Packed Bools) | ISO C | ⚪ Planned |
| **F-34** | Peephole | Pass-5 Bit-Test Optimization (`BIT`, `BMI`, `BPL` for Bit 7/6) | Pass 5 | ⚪ Planned |
| **F-35** | Codegen | Multi-Entry Byte-Skipping via `BIT $xxxx` (`.byte $2c`) | 6502 Idiom | ✅ Completed |
| **F-36** | Types | Packed 4-Bit Nibble Arrays (`uint4_t` / Color-RAM Packing) | C64 Architecture | ⚪ Planned |

---

## 3. Detailed Specification: Planned Low-Level 6502 Optimizations

### Feature 02: C11 Anonymous Unions & 2–4 Byte Assignment Loop-Unrolling
* **Goal:** Direct 16/32-bit field aliasing without intermediate named structs, and unrolling small `struct` assignments into direct `LDA`/`STA` pairs rather than emitting overhead-heavy memory copy loops.

### Feature 04: Zero-Syntax Pointer-to-SMC Uplift & Dynamic Call Dispatch (RAM)
* **Goal:** 
  1. For code marked in `RAM_RESIDENT`, detect hot sequential pointer loops and transform indirect indexed addressing (`LDA ($zp),Y`) into direct self-modifying immediate operands (`LDA $xxxx`). Saves 2 cycles per byte and frees Zero-Page registers.
  2. For dynamic function pointer calls, replace heavy Zero-Page indirect trampolines (`JMP ($zp)`) with direct self-modifying `JSR $0000` operand patching in RAM, executing dynamic dispatches in a native 6 cycles.

### Feature 09: 8-Bit Indexing for $\le 128$ Word-Arrays & `__lohi` Split Arrays
* **Goal:** When indexing 16-bit word arrays with $\le 128$ elements, avoid 16-bit pointer arithmetic. Generate `ASL; TAX; LDA tab,X; LDA tab+1,X` (12 cycles) or split low/high arrays `LDA tab_lo,X; LDY tab_hi,X` (8 cycles).

### Feature 10: Struct-of-Arrays (`__soa`) Automatic Flattening
* **Goal:** Automatically transpose arrays of structures (`AOS`) into parallel arrays of primitive fields (`SOA`), enabling single-instruction indexed addressing (`LDA player_x,X`) rather than pointer multiplication.

### Feature 11: 16-Bit Register Pair `AX` for Return Values & Parameters
* **Goal:** Allow functions returning 16-bit values to pass the low byte in `A` and the high byte in `X`, avoiding Zero-Page allocation for function returns and leaf parameters.

### Feature 16: Compact 8x8->16 Multiplication without 16-Bit Zero Extension
* **Status:** ✅ Completed in `v0.10.0-dev`.
* **Summary:** In Pass 2 (`Pass2MultiplyToShiftRewriting`), factors out trailing zeros $2^k$ and recovers underlying 8-bit operands from SSA casts, emitting compact shift-add decomposition that promotes to 16-bit before final shifting. Bit-exact across the full 8-bit domain ($0..255$), saving over 50% code size and eliminating intermediate 16-bit register allocations. Pass 5 multi-label predecessor tracking hardened.

---

## 4. Detailed Specification: Modern C Standards Track (C11 / C23 / C2Y)

### Feature 17: `#embed` Direct Binary Asset Embedding (C23)
* **Status:** ✅ Completed in `v0.10.0-dev`.
* **Summary:** Implemented standard C23 `#embed` directive in `CPreprocessor` and `KickCLexer`, expanding directly to integer constant token sequences. Full support for C23 parameters `limit(N)`, `prefix(...)`, `suffix(...)`, and `if_empty(...)`, as well as `__has_embed(...)` and `__has_include(...)` preprocessor conditional operators with standard `__STDC_EMBED_*__` macros.
* **Problem:** 8-Bit software relies heavily on raw binary assets (character sets, sprite data, tilemaps, chiptune music). Developers previously had to convert binaries into C arrays using external preprocessing scripts or inline assembler directives.
* **Syntax:**
  ```c
  const uint8_t charset[] = {
      #embed "assets/font.bin"
  };
  ```
* **Compiler Integration:**
  * Implemented in the preprocessor and Pass 0 AST visitor.
  * Direct stream-expansion into array initializer expressions or optimized `.import binary` representations in KickAssembler output.

---

### Feature 18: Typed 8-Bit Enumerations `enum : uint8_t` (C23)
* **Status:** ✅ Completed in `v0.10.0-dev`.
* **Summary:** Enforces `BYTE_UNSIGNED` as the underlying representation in Pass 1, guaranteeing 1-byte storage and eliminating 16-bit integer-promotion overhead.

---

### Feature 19: Compile-Time Array Length Operator `countof()` (C2Y / C29)
* **Status:** ✅ Completed in `v0.10.0-dev`.
* **Summary:** Evaluated at compile-time in Pass 0/1 constant resolution as an immediate integer literal, replacing error-prone `sizeof(arr)/sizeof(arr[0])`.

---

### Feature 20: Named Loops: `break label;` & `continue label;` (C2Y / C29)
* **Status:** ✅ Completed in `v0.10.0-dev`.
* **Summary:** Pass 3 CFG routes the break edge directly to the exit block of the designated loop, emitting a single 6502 branch or jump instruction.

---

### Feature 21: Compile-Time Assertions `static_assert` (C11 / C23)
* **Status:** ✅ Completed in `v0.10.0-dev`.
* **Summary:** Evaluated during Pass 0/1 constant resolution, aborting compilation with clear error messages if violated.

---

### Feature 22: `_Generic` Compile-Time Type Dispatch (C11)
* **Syntax:**
  ```c
  #define abs(x) _Generic((x), \
      int8_t: abs8(x), \
      int16_t: abs16(x) \
  )
  ```
* **Compiler Integration:** Resolves polymorphic macros at compile time without runtime dispatch overhead.

---

### Feature 23: Digit Separators (`0b1100_0011`, `1_000`) & C23 Keywords
* **Status:** ✅ Completed in `v0.10.0-dev`.
* **Summary:** Underscores supported in integer literals in `KickCLexer.g4`, plus native recognition of `nullptr`, `true`, `false`, and empty struct initialization `{}`.

---

### Feature 24: `if`- & `switch`-Statements with Initializers (C2Y / C29)
* **Status:** ✅ Completed in `v0.10.0-dev`.
* **Summary:** Confines scope strictly to the conditional block in Pass 1, shortening live ranges and enabling aggressive Zero-Page register reuse in Pass 4.

---

## 5. Detailed Specification: 6502 Hardware & Idiom Optimizations

### Feature 25: Auto Carry-Flag Boolean Returns (`__carry` / Auto-Carry ABI)
* **Status:** ✅ **Implemented & Verified (Commit 7397456c6)**.
* **Summary:** Implemented `__carry` directive and whole-program auto-carry return optimization in `Pass5CarryFlagReturnOptimization`. Redundant boolean register staging (`lda #0; rol`) is collapsed, callee returns comparison results directly in Carry flag (`cmp/cpy/cpx; rts` or `sec/clc; rts`), and caller branches directly via `bcs`/`bcc`, eliminating `cmp #0` and zero-page staging. Tested bit-exact in VICE on port 6541.
* **Problem:** Standard C functions returning boolean values store `0` or `1` into a Zero-Page register via `LDA #0/#1; STA return_val`. The caller then re-loads it and executes `CMP #0; BNE/BEQ`. This costs ~12 cycles and burns a Zero-Page register.
* **6502 Idiom:** In 6502 assembly, condition checks return results in the **Carry flag** (`SEC` for true, `CLC` for false), and callers branch immediately via `BCS` or `BCC`.
* **Compiler Integration:**
  * **Fully Automatic (Whole-Program Analysis):** If Pass 4/5 detects that a function's boolean return value is strictly used in conditional branches (`if`, `while`, `&&`, `||`) across all call sites, the compiler automatically synthesizes:
    * Callee: Ends with `SEC; RTS` or `CLC; RTS`.
    * Caller: Emits immediate `BCS` or `BCC`.
  * **Explicit Attribute:** Optional `__carry bool func()` or `[[carry_return]]` for exported assembly interfaces.
* **Savings:** Saves 10–12 cycles, 6–8 bytes of code, and 1 Zero-Page byte per call site.

---

### Feature 26: Leaf-Function Automatic Register Parameter Passing (A, X, Y)
* **Problem:** Even small leaf functions allocate dedicated Zero-Page parameter memory, leading to memory store instructions prior to every `JSR`.
* **6502 Idiom:** Small leaf routines take up to three byte arguments directly in CPU registers `A`, `X`, and `Y`.
* **Compiler Integration:**
  * Whole-program analysis in Pass 4 identifies leaf functions (no child calls).
  * The first 3 unsigned byte arguments are mapped directly to physical registers: `arg0 -> A`, `arg1 -> X`, `arg2 -> Y`.
  * Eliminates Zero-Page allocation and eliminates `STA param_zp` at call sites.

---

### Feature 27: 8.8 Fixed-Point Data Type (`fixed8_8` / ISO TR 18037) & 0-Cycle Shift Fusing
* **Problem:** 2D retro games calculate physics, velocity, and subpixel positions using 8.8 fixed-point numbers. In standard C, using `uint16_t` requires awkward downcasts `(uint8_t)(pos >> 8)` that frequently compile to expensive 8-step runtime shift loops.
* **6502 Idiom:** Reading the integer part of an 8.8 fixed-point variable on 6502 is a **zero-cycle operation**: simply read the high byte (`LDA pos+1`)!
* **Compiler Integration:**
  1. Introduction of `fixed8_8` (or `short _Accum` per ISO TR 18037).
  2. Compile-time literal folding: `fixed8_8 vel = 1.25;` folds directly to constant `$0140`.
  3. Expression `(uint8_t)pos` compiles directly to `LDA pos+1` with zero shifting.
  4. Multiplication `pos * vel` emits table-driven 8.8 fixed-point multiplication.

---

### Feature 28: BCD Decimal Accumulator Arithmetic (`__bcd` / `SED` Support)
* **Problem:** Game scores, player cash, and timer counters require frequent conversion between binary and decimal for on-screen display, consuming hundreds of cycles in division subroutines.
* **6502 Idiom:** The 6502 hardware Decimal Mode (`SED`) natively executes additions and subtractions directly in Binary Coded Decimal (BCD). Screen display is trivial since each nibble directly corresponds to an ASCII character (`+ '0'`).
* **Compiler Integration & Safety Guards:**
  * Supported as a specialized accumulator type `__bcd uint16_t` or `__bcd uint32_t`, supporting `+=`, `-=`, `++`, `--`, and comparison.
  * **Critical IRQ Safety:** On NMOS 6502, hardware interrupts do not automatically clear the `D` flag. The compiler strictly wraps BCD operations (`SED; ADC ...; CLD;`) or enforces `CLD` insertion in all interrupt handlers.

---

### Feature 29: Hardware Stack-RAM Segment (`$0100–$01AF` Scratchpad Buffer)
* **Problem:** The MOS 6502 hardware stack occupies Page 1 (`$0100–$01FF`). Because KickC uses a static call graph rather than dynamic stack frames, the active stack rarely exceeds 15–20 bytes (remaining above `$01E0`). Consequently, ~176 bytes in `$0100–$01AF` sit completely unutilized.
* **Compiler Integration:**
  * Predefined memory segment `STACK_RAM` mapping memory addresses `$0100` to `$01AF`.
  * Provides an 80-byte safety buffer below the maximum possible descent of the hardware stack pointer.
  * Allows developers to place transient buffers (e.g. screen fade buffers, decompression scratchpads, tile staging arrays) via `#pragma data_seg(StackRam)`.
  * **Gain:** Frees up to 176 bytes of valuable resident main memory below `$5600`.

---

### Feature 30: Cycle-Exact Delay Synthesizer (`delay_cycles(N)` in `<6502.h>`)
* **Problem:** Raster splits, VIC-II border opening, and hardware register changes require cycle-exact delays to avoid 1-pixel video glitches. C loops introduce compiler-dependent jitter.
* **Syntax:** `delay_cycles(18);` (parameter must be a compile-time constant).
* **Compiler Integration:**
  * Pass 5 peephole synthesizer generates the optimal cycle-exact, register-safe instruction sequence:
    * 2 cycles: `NOP` (1 byte)
    * 3 cycles: `BIT $00` (2 bytes, preserves A, X, Y)
    * 4 cycles: `NOP; NOP` (2 bytes)
    * 5 cycles: `BIT $00; NOP` (3 bytes)
    * 6 cycles: `PHA; PLA` (2 bytes)
    * 7 cycles: `PHA; PLA; NOP` (3 bytes)
    * $\ge 8$ cycles: Synthesized combination of `PHA; PLA`, `BIT`, `NOP`, or unrolled decrement loops.

---

### Feature 31: Pre-Indexed Zero-Page Addressing Optimization (`LDA ($zp,X)`) - [x] COMPLETED
* **Problem:** Accessing an array of 16-bit pointers (`ptr_table[x][0]`) previously copied the pointer from the array into two temporary Zero-Page bytes and executed `LDA ($zp),Y`, taking 20–25 cycles and burning Zero-Page registers.
* **6502 Idiom:** When an array of pointers is placed in the Zero Page, the 6502 instruction `LDA.Z ($zp,X)` / `STA.Z ($zp,X)` dereferences the pointer directly in a single 6-cycle operation.
* **Compiler Integration:**
  * Pass 5 `Pass5PreIndexedAddressingOptimization` optimizes multi-instruction pointer dereferencing sequences into native `LDA.Z (zp_table,X)` and `STA.Z (zp_table,X)`.
  * Updated `CpuAddressingMode.IZX` to emit `%i.z (%p,x)` ensuring unambiguous KickAssembler opcode synthesis.
  * Enhanced `Pass4CodeGeneration` to emit `.label` declarations for Zero-Page arrays without corrupting PRG load headers.
  * Verified bit-exact in VICE on port 6541 with screen test output `PASS!`.

---

### Feature 32: Compile-Time Table Generator (`__builtin_lut` / Constexpr Arrays) - [x] COMPLETED
* **Problem:** 6502 games rely on precalculated look-up tables (sine waves, circles, easing curves, color ramps). KickAssembler natively supports this via `.fill 256, sin(i*PI*2/256)*64+100`. In C, developers previously had to maintain external Python generation scripts.
* **Syntax:**
  ```c
  const uint8_t sin_table[256] = __builtin_lut(256, round(sin(i * 2.0 * 3.14159265 / 256.0) * 64.0 + 128.0));
  ```
* **Implementation:**
  * Pass 0 intercepts `__builtin_lut(size, expr)` array initializations and generates KickAssembler `.fill` (for 1-byte elements), `.fillword` (for 2-byte elements), or `.for(var i=0; i<size; i++) .dword expr` (for 4-byte elements) directives directly into the variable's constant segment.
  * Math constants (`PI`, `M_PI`) and double-precision math functions (`sin`, `cos`, `tan`, `atan`, `round`, `floor`, `ceil`, `sqrt`, `pow`) are evaluated at assembly time with full 64-bit precision.
  * Eliminates external asset generation scripts for algorithmic tables. Verified bit-exact in VICE on port 6541.

---

### Feature 33: ISO-C Bitfields in Structs (`uint8_t flag : 1;`)
* **Problem:** Boolean variables occupy a full 8-bit byte in RAM. In games with numerous entities (e.g. 50 ships with 8 status flags each), this consumes 400 bytes instead of 50 bytes of valuable RAM.
* **Syntax:**
  ```c
  struct ShipStatus {
      uint8_t in_harbor   : 1;
      uint8_t is_pirate   : 1;
      uint8_t is_damaged  : 1;
      uint8_t has_captain : 1;
      uint8_t fleet_id    : 4; // Sub-byte 4-bit integer (0..15)
  };
  ```
* **Compiler Integration:**
  * Implemented in `CParserVisitor` and Pass 1 AST member layout.
  * Packs adjacent bitfields of the same underlying byte type into single bytes.
  * Pass 4/5 automatically synthesizes minimal bitmask instructions:
    * Read: `LDA byte; AND #mask; BNE` (or `LSR/ASL` for sub-byte integers).
    * Set: `LDA byte; ORA #mask; STA byte`.
    * Clear: `LDA byte; AND #~mask; STA byte`.
  * Drastically reduces data structure RAM footprint for game entities.

---

### Feature 34: Pass-5 Bit-Test Optimization (`BIT`, `BMI`, `BPL` for Bit 7 & 6)
* **Problem:** Checking bit flags (e.g. `if (flags & 0x80)`) currently generates `LDA flags; AND #$80; BNE`, costing 7 cycles and clobbering the Accumulator register.
* **6502 Idiom:** The 6502 instruction `BIT abs` copies bit 7 into the Negative flag (N) and bit 6 into the Overflow flag (V) without altering the Accumulator!
* **Compiler Integration:**
  * Pass 5 peephole optimizer recognizes bitmask checks against `0x80` (bit 7) and `0x40` (bit 6).
  * Synthesizes `BIT flags; BMI label` (if bit 7 is set) or `BIT flags; BPL label` (if bit 7 is clear).
  * Saves 2 cycles and preserves register `A`, allowing the subsequent code to continue using the accumulator.

---

### Feature 35: Multi-Entry Byte-Skipping via `BIT $xxxx` (`.byte $2c`)
* **Problem:** Functions that share an identical body except for an immediate constant loaded at the start (e.g. `set_border_red()` vs `set_border_blue()`) duplicate machine code or require extra `JMP` instructions.
* **6502 Idiom:** In 6502 assembly, functions share a single body by having the first entry point execute `LDA #val1`, followed by opcode `$2C` (`BIT $xxxx`). When entering at the first entry, `$2C` consumes the following 2-byte instruction `LDA #val2` as an operand to `BIT`, skipping it in 4 cycles without clobbering registers A, X, or Y.
* **Compiler Integration:**
  * Pass 5 function merger detects tail-identical functions differing only by a leading 2-byte immediate load into the same register.
  * Emits shared function body with intermediate `.byte $2c` byte-skipper.
  * Saves 4 bytes and eliminates jump overhead per merged entry.

---

### Feature 36: Packed 4-Bit Nibble Arrays (`uint4_t` / Color-RAM Packing)
* **Problem:** Color-RAM (`$D800–$DBE7`, 1000 characters) on the C64 utilizes only 4 bits per byte (values 0..15). Allocating 8-bit byte arrays (`uint8_t buffer[1000]`) for screen fading or tile attribute maps wastes 500 bytes of scarce RAM.
* **Syntax:**
  ```c
  uint4_t color_fade_buffer[1000]; // Occupies exactly 500 bytes in memory
  ```
* **Compiler Integration:**
  * `uint4_t` represents a packed 4-bit unsigned nibble type.
  * Array indexing `buffer[i]` automatically emits:
    * Shift index `i >> 1` (`LSR`).
    * If `i & 1 == 0`: read low nibble (`AND #$0F`).
    * If `i & 1 != 0`: read high nibble (`LSR` 4 times or `SWAP/AND`).
  * Direct 50% RAM reduction for graphics, tile, and Color-RAM storage.
