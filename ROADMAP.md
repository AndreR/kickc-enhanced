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

## 2. Feature Matrix (Strictly Sequenced F-00 to F-49)

| ID | Category | Feature Description | Origin / Standard | Status |
| :---: | :--- | :--- | :---: | :---: |
| **F-00** | Architecture | Segment Type Classification (`#pragma segment_type`) & Cross-Bank Verification | KickC Ext | ✅ Completed |
| **F-01** | Codegen | Split Jump-Tables for dense `switch` via 12-cycle RTS dispatch | 6502 Idiom | ✅ Completed |
| **F-02** | Types | C11 Anonymous Unions & 2–4 Byte Assignment Loop-Unrolling | C11 | ✅ Completed |
| **F-03** | Intrinsics | Atomic Status Intrinsics (`__builtin_bit7/6`, `SEC`, `CLC`, etc.) | KickC Ext | ✅ Completed |
| **F-04** | Codegen | Zero-Syntax Auto Pointer-to-SMC & Dynamic Call Dispatch (RAM) | 6502 Idiom | ⚪ Planned |
| **F-05** | Assembler | Zero-Waste Conditional Page-Boundary Alignment (`.align $100`) | KickAss Ext | ✅ Completed |
| **F-06** | Allocator | Inter-Segment Zero-Page Coalescing across disjoint data segments | KickC Core | ✅ Completed |
| **F-07** | Controlflow | Count-Down Loop-Inversion (`BPL`) & `__countdown` directive | 6502 Idiom | ✅ Completed |
| **F-08** | Numerics | Bit-exact Quarter-Square Table Multiplication (`<multiply.h>`) | 6502 ROM | ✅ Completed |
| **F-09** | Arrays | 8-Bit Indexing for $\le 128$ Word-Arrays & `__lohi` Split Arrays | 6502 Idiom | ✅ Completed |
| **F-10** | Data Layout | Struct-of-Arrays (`__soa`) Automatic Flattening | Data Layout | ✅ Completed |
| **F-11** | ABI | 16-Bit Hardware Register Pair `AX` for Return Values & Params | Calling Conv | ✅ Completed |
| **F-12** | Peephole | Pass-5 Flag Tracking (N/Z-preservation across stores & status ops) | Pass 5 | ✅ Completed |
| **F-13** | Arithmetics | Reciprocal Constant Division & Modulo (`/ 10`, `% 10`) | Pass 2 SSA | ✅ Completed |
| **F-14** | Interprocedural | Read-Only Zero-Page Parameter Preservation across `JSR` | Pass 5 | ✅ Completed |
| **F-15** | Codegen | Page-Aligned 16-Bit Pointer Addition Folding (`#<$xx00`) | Pass 5 | ✅ Completed |
| **F-16** | Arithmetics | Compact 8x8->16 Multiplication without 16-bit Zero-Extension | Pass 2 SSA | ✅ Completed |
| **F-17** | Preprocessor | `#embed` Directive for Direct Binary Asset Embedding | C23 | ✅ Completed |
| **F-18** | Types | Typed 8-Bit Enumerations (`enum : uint8_t`) | C23 | ✅ Completed |
| **F-19** | Operators | Compile-Time Array Length Operator `countof()` | C2Y (C29) | ✅ Completed |
| **F-20** | Controlflow | Named Loops: `break label;` & `continue label;` | C2Y (C29) | ✅ Completed |
| **F-21** | Safety | Compile-Time Assertions (`static_assert` / `_Static_assert`) | C11 / C23 | ✅ Completed |
| **F-22** | Metaprog | `_Generic` Type-Dispatch for Polymorphic 8/16-Bit Macros | C11 | ✅ Completed |
| **F-23** | Lexer | Digit Separators (`0b1100_0011`, `1_000`) & C23 Keywords (`nullptr`, `{}`) | C23 | ✅ Completed |
| **F-24** | Controlflow | `if`- & `switch`-Statements with Initializers | C2Y (C29) | ✅ Completed |
| **F-25** | ABI / Opt | Auto Carry-Flag Boolean Returns (`__carry` / Auto-Carry ABI) | 6502 Idiom | ✅ Completed |
| **F-26** | ABI / Opt | Leaf-Function Automatic Register Parameter Passing (A, X, Y) | Calling Conv | ✅ Completed |
| **F-27** | Types | 8.8 Fixed-Point Data Type (`fixed8_8`) & 0-Cycle Shift Fusing | ISO TR 18037 | ✅ Completed |
| **F-28** | Numerics | BCD Decimal Accumulator Arithmetic (`__bcd` / `SED` Support) | 6502 Hardware | ✅ Completed |
| **F-29** | Memory | Hardware Stack-RAM Segment (`$0100–$01AF` Scratchpad Buffer) | C64 Architecture | ✅ Completed |
| **F-30** | Timing | Cycle-Exact Delay Synthesizer (`delay_cycles(N)` in `<6502.h>`) | Hardware Timing | ✅ Completed |
| **F-31** | Codegen | Pre-Indexed Zero-Page Addressing Optimization (`LDA ($zp,X)`) | 6502 Hardware | ✅ Completed |
| **F-32** | Generator | Compile-Time Table Generator (`__builtin_lut` / Constexpr Arrays) | Tooling | ✅ Completed |
| **F-33** | Types | ISO-C Bitfields in Structs (`uint8_t a:1, b:1;` for Packed Bools) | ISO C | ✅ Completed |
| **F-34** | Peephole | Pass-5 Bit-Test Optimization (`BIT`, `BMI`, `BPL` for Bit 7/6) | Pass 5 | ✅ Completed |
| **F-35** | Codegen | Multi-Entry Byte-Skipping via `BIT $xxxx` (`.byte $2c`) | 6502 Idiom | ✅ Completed |
| **F-36** | Types | Packed 4-Bit Nibble Arrays (`uint4_t` / Color-RAM Packing) | C64 Architecture | ⚪ Planned |
| **F-37** | Library | Library Bugfix & Acceleration: `string.h` (`memcpy_fast` fix, page-copy loop) | 6502 Library | ✅ Completed |
| **F-38** | Library | Library: `stdlib.h` (`RAND_MAX` bugfix, polymorphic `abs` via C11 `_Generic`) | C11 Library | ✅ Completed |
| **F-39** | Library | Library: `ctype.h` / `ctype.c` Standard Conformance (`tolower`, `isdigit`, etc.) | ISO C Library | ✅ Completed |
| **F-40** | Library | Library: `conio-cbm-shared.c` Performance & C23 `bool` Modernization | CBM Library | ✅ Completed |
| **F-41** | Library | Library: `c64-bitmap.c` ROM Safety Fix & 8-Bit Y Bresenham Routine | C64 Library | ✅ Completed |
| **F-42** | Library | Library: `printf.c` / `sprintf.c` 8/16-Bit Fast-Path Dispatch | 6502 Library | ✅ Completed |
| **F-43** | Library | Library Bugfix: `stdint.h` & `limits.h` (32-Bit Limits, Macro Conversion, `limits.h`) | ISO C Library | ✅ Completed |
| **F-44** | Library | Library Bugfix: `sine.c` (`sin16s_gen2` Precedence) & `c64-tod.c` (AM/PM Masking) | 6502 Library | ✅ Completed |
| **F-45** | Math | Library Math: Fast 6502 Division & Zero-RAM Square Root (`sqrt16`, `div16u`) | 6502 Math | 🟢 Completed |
| **F-46** | Math | Library Math: Trig & Multiplier Optimization (Sine Quadrant Symmetries, CORDIC) | 6502 Math | ⚪ 🟢 Completed |
| **F-47** | Library | Library: High-Performance Retro-Game Utilities (PRNG Suite, Multiplexer, Fast `strlen`) | 6502 Library | ⚪ 🟢 Completed |
| **F-48** | Hardware | Library C64: Hardware Drivers (Commodore 1351 Mouse, REU DMA & Raster Sync) | C64 Hardware | 🟢 Completed |
| **F-49** | Graphics | Library C64: Asset & Graphics Pipeline (Universal ZX02 Decompressor, Fading) | C64 Graphics | 🟢 Completed |
| **F-50** | I/O | Library C64: High-Speed Disk I/O Suite (Hardware Probing, Turbo Read/Write, ELoad) | C64 I/O | 🟢 Completed |
| **F-51** | Compression | Library: Ultra-Fast Run-Length Encoding (RLE) Compression Suite (<rle.h>) | 6502 Library | 🟢 Completed |

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

### Feature 11: 16-Bit Hardware Register Pair `AX` Calling Convention (__ax)
* **Status:** ✅ **Implemented & Verified (Issue #12)**.
* **Summary:** Implemented `__ax` directive in grammar and AST, enabling the standard 6502 16-bit calling convention:
  * Functions marked with `__ax` or returning 16-bit integers return the low byte in accumulator `A` and high byte in index register `X`.
  * Single 16-bit parameter functions receive arguments directly in `A` (low) and `X` (high), eliminating Zero-Page setup at call sites.
  * Direct support for constant returns (`LDA #<val; LDX #>val; RTS`), dynamic computed returns (`TAX; LDA return_lo; RTS`), and memory/pointer returns (`LDA ptr; LDX ptr+1; RTS`).
  * Enables zero-overhead function chaining (e.g. `f(g(x))` passes result directly in `A`/`X` without intervening Zero-Page loads/stores).
  * Hardened with `setNoOptimize(true)` in `AsmLine` to protect calling convention ABI boundaries against subsequent peephole passes.
  * Verified bit-exact in headless VICE on port 6541 across all 5 test scenarios (`PASS!`).

### Feature 16: Compact 8x8->16 Multiplication without 16-Bit Zero Extension
* **Status:** ✅ Completed in `v0.10.0-dev`.
* **Summary:** In Pass 2 (`Pass2MultiplyToShiftRewriting`), factors out trailing zeros $2^k$ and recovers underlying 8-bit operands from SSA casts, emitting compact shift-add decomposition that promotes to 16-bit before final shifting. Bit-exact across the full 8-bit domain ($0..255$), saving over 50% code size and eliminating intermediate 16-bit register allocations. Pass 5 multi-label predecessor tracking hardened.

---

## 4. Detailed Specification: Modern C Standards Track (C11 / C23 / C2Y)

### Feature 2: C11 Anonymous Unions & 2–4 Byte Assignment Loop-Unrolling (C11)
* **Status:** ✅ **Implemented & Verified (Commit 76c5d183f)**.
* **Summary:** Added full support for ISO C11 §6.7.2.1 anonymous structures and unions (`structDef ';'` in `structMembers`), recursively flattening anonymous members into the enclosing structure/union with precise byte offset calculation. Added `Pass5SmallMemcpyUnrollingOptimization` to unroll small 2..4 byte struct/union copy loops (both forward and countdown patterns) into direct inline `LDA/STA` pairs, completely eliminating loop branches and register pressure. Tested bit-exact in VICE on port 6541.
* **Syntax:**
  ```c
  struct Vector {
      union {
          struct { uint8_t x, y; };
          uint16_t raw;
      };
      uint8_t id;
  };
  ```

---

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
  * Whole-program analysis in `Pass4RegisterUpliftPotentialInitialize` identifies leaf functions (no outgoing calls, not interrupts, not stackcall).
  * Up to 3 8-bit byte arguments prioritize hardware registers: `arg0 -> A`, `arg1 -> X`, `arg2 -> Y`, retaining `defaultRegister` (Zero Page) as clean fallback.
  * `PassNCalcVariableRegisterWeight` boosts leaf procedure parameter weights to prioritize them over multi-caller live range sizes.
  * `Pass4AssertNoCpuClobber` safely rejects register candidates and falls back to Zero-Page if internal expressions or asm clobber registers.
  * Eliminates Zero-Page allocation and eliminates `STA param_zp` at call sites.
  * Verified bit-exact in VICE on port 6541 (`PASS!`). (Issue #27).

---

### Feature 27: 8.8 Fixed-Point Data Type (`fixed8_8` / ISO TR 18037) & 0-Cycle Shift Fusing
* **Problem:** 2D retro games calculate physics, velocity, and subpixel positions using 8.8 fixed-point numbers. In standard C, using `uint16_t` requires awkward downcasts `(uint8_t)(pos >> 8)` that frequently compile to expensive 8-step runtime shift loops.
* **6502 Idiom:** Reading the integer part of an 8.8 fixed-point variable on 6502 is a **zero-cycle operation**: simply read the high byte (`LDA pos+1`)!
* **Compiler & Library Integration:**
  1. Header `<fixedpoint.h>` defines types `fixed8_8` and `ufixed8_8`.
  2. Compile-time literal folding in `NumberParser`: floating-point literals (e.g. `1.25`) fold directly to scaled 8.8 constants (`$0140`).
  3. Expression `fixed_int(pos)` / `(pos >> 8)` compiles directly to 0-cycle high-byte load `LDA pos+1`.
  4. Bit-exact verified in VICE on port 6541 (`PASS!`). (Issue #28).

---

### Feature 28: BCD Decimal Accumulator Arithmetic (`__bcd` / `SED` Support)
* **Problem:** Game scores, player cash, and timer counters require frequent conversion between binary and decimal for on-screen display, consuming hundreds of cycles in division subroutines.
* **6502 Idiom:** The 6502 hardware Decimal Mode (`SED`) natively executes additions and subtractions directly in Binary Coded Decimal (BCD). Screen display is trivial since each nibble directly corresponds to an ASCII character (`+ '0'`).
* **Compiler & Library Integration:**
  1. <bcd.h> and lib/bcd.c implement types cd8_t, cd16_t, cd32_t, macros BCD8() / BCD16(), and 8/16/32-bit operations (cd8_add, cd8_sub, cd8_inc, cd8_dec, cd16_add, cd16_sub, cd16_inc, cd16_dec, cd32_add, cd32_sub).
  2. Zero-division string conversions: cd8_to_str, cd16_to_str, cd32_to_str, in_to_bcd8, cd8_to_bin.
  3. **Critical IRQ Safety:** Eliminated NMOS 6502 IRQ Decimal Mode trap by adding cld instruction to isr_hardware_all_entry.asm.
  4. Bit-exact verified in VICE on port 6541 (PASS!). (Issue #29, Commit 4cd748875).

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
  * Implemented in ANTLR4 KickCParser.g4 (grammar rules for named and unnamed bitfields : expr).
  * Variable symbol metadata stores itFieldWidth, itFieldOffset, and underlying container member name (__bf_N).
  * StructDefinition and SymbolTypeStruct automatically calculate struct byte size and member offsets solely from physical storage containers, ensuring accurate sizeof and alignment.
  * Pass 0 AST desugaring:
    * Read: Extracts value via (container >> offset) & mask with branchless (val ^ signBit) - signBit sign extension for signed bitfields.
    * Write: Synthesizes optimal bitmask operations container = (container & invMask) | insertBits, with specialized fast paths for boolean flags and constant values.
    * Full support for pointer dereferencing (ptr->field), compound assignments (ptr->field += 2), and pre/post-increments (ield++).
  * Verified bit-exact in headless VICE on port 6541 (Issue #34).

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

---

### Feature 37: Library Bugfix & Acceleration: `string.h` (`memcpy_fast` fix, page-copy loop)
* **Problem:** 
  1. `string.c` contains an off-by-one out-of-bounds error in `memcpy_fast` and `memset_fast`: `do { *(destination+num) = *(source+num); num--; } while(num);` accesses `offset = num` down to `1`, writing one byte past the destination buffer and completely skipping index `0`.
  2. Standard `memcpy`, `memset`, and `memmove` rely on sequential 16-bit pointer increments, which execute at roughly 28 cycles/byte.
  3. Several standard string routines (`strchr`, `strrchr`, `strcmp`, `strcat`, `strncat`, `strstr`) are absent from `<string.h>`.
* **Solution & Optimization:**
  * Fix the loop indexing in `memcpy_fast` and `memset_fast` (`*(destination+--num) = *(source+--num)` or post-decrement loop).
  * Implement unrolled page-copy transfer loops (`LDY #0; loop: LDA (src),Y; STA (dst),Y; INY; BNE loop; INC src+1; INC dst+1; ...`) for bulk transfers $\ge 256$ bytes, cutting copy time down to ~12 cycles/byte.
  * Provide standard-compliant 6502 implementations for missing string utility functions.

---

### Feature 38: Library: `stdlib.h` (`RAND_MAX` bugfix, polymorphic `abs` via C11 `_Generic`)
* **Problem:**
  1. `stdlib.h` defines `#define RAND_MAX 65335` (typo for `65535`).
  2. Calling `abs()` on an 8-bit `signed char` either promotes unnecessarily to 16-bit or lacks type safety.
  3. Integer-to-string routines (`uctoa`, `utoa`, `ultoa`) execute full division loops even for common radix 10 and 16.
* **Solution & Optimization:**
  * Correct `RAND_MAX` to `65535`.
  * Add `abs8(signed char x)` and define polymorphic `abs(x)` using C11 `_Generic` (`F-22`):
    ```c
    #define abs(x) _Generic((x), \
        signed char: abs8(x), \
        int: abs16(x), \
        long: labs(x) \
    )
    ```
  * Specialize `uctoa` and `utoa` for radix 10 and 16, utilizing the reciprocal division algorithms from `F-13`.

---

### Feature 39: Library: `ctype.h` / `ctype.c` Standard Conformance (`tolower`, `isdigit`, etc.)
* **Problem:** The compiler library currently only defines `toupper(ch)` in `<ctype.h>`. Standard character classification and conversion functions are completely missing.
* **Solution & Optimization:**
  * Implement full ISO C character classification functions optimized for 6502 without unnecessary table overhead:
    * `isdigit(c)`: `(unsigned char)(c - '0') < 10`
    * `isxdigit(c)`: Range check `'0'..'9'`, `'a'..'f'`, `'A'..'F'`
    * `isalpha(c)`, `isalnum(c)`, `isspace(c)`, `isupper(c)`, `islower(c)`, `isprint(c)`, `ispunct(c)`
    * `tolower(c)`: Inverse of `toupper(c)`
  * Leverage `__carry` Boolean return ABI (`F-25`) to return condition results directly in the Carry flag.

---

### Feature 40: Library: `conio-cbm-shared.c` Performance & C23 `bool` Modernization
* **Problem:**
  1. `clrscr()` clears the screen via 1,000 iterations of 16-bit pointer additions (`screen[i] = ' '`), which is visibly slow.
  2. `cscroll()` performs line-by-line copies with 16-bit pointer calculations.
  3. `kbhit()`, `cursor()`, and `scroll()` use `unsigned char` and integer flags rather than modern C23 `bool`.
  4. `kbhit()` in `conio-c64.c` accesses CIA1 Port A without restoring original state.
* **Solution & Optimization:**
  * Unroll `clrscr()` into 4 fast 256-byte loop blocks (`STA screen,X; STA screen+$100,X; STA screen+$200,X; STA screen+$2E8,X; DEX; BNE ...`).
  * Modernize `conio` API signatures to utilize C23 `bool`, `true`, and `false`, returning via `F-25` Carry flag.
  * Ensure keyboard matrix probing cleanly restores CIA1 Port A state.

---

### Feature 41: Library: `c64-bitmap.c` ROM Safety Fix & 8-Bit Y Bresenham Routine
* **Problem:**
  1. `bitmap_plot_ylo`, `bitmap_plot_yhi`, and `bitmap_plot_bit` are declared `const` but mutated at runtime in `bitmap_init()`, violating ROM safety (`#pragma segment_type(..., ROM)`).
  2. Bresenham line algorithm (`bitmap_line`) performs all calculations in 16-bit, even though the vertical coordinate `y` on C64 standard bitmap mode never exceeds 199 ($00..$C7, strictly 8-bit).
* **Solution & Optimization:**
  * Precalculate plot lookup tables at compile time via `F-32` (`__builtin_lut`) and place them cleanly in ROM, or declare them non-const in RAM.
  * Provide an optimized `bitmap_line8(uint16_t x1, uint8_t y1, uint16_t x2, uint8_t y2)` utilizing 8-bit Zero-Page registers for vertical calculations, speeding up line drawing significantly.

---

### Feature 42: Library: `printf.c` / `sprintf.c` 8/16-Bit Fast-Path Dispatch
* **Problem:** Standard `printf` casts every integer argument to a 32-bit `unsigned long` and invokes 32-bit `ultoa()`. On the 6502, 32-bit division and modulo operations are slow and consume significant Zero-Page scratchpad registers.
* **Solution & Optimization:**
  * Introduce fast-path dispatch in format parsing:
    * 8-bit integer formats (`%hhu`, `%hhd`, `%hhx`) dispatch directly to 8-bit `uctoa()`.
    * 16-bit integer formats (`%u`, `%d`, `%x`) dispatch directly to 16-bit `utoa()`.
    * Only 32-bit formats (`%lu`, `%ld`, `%lx`) invoke full 32-bit `ultoa()`.
  * Reduces cycle count by up to 70% when printing byte and word values.

---

### Feature 43: Library Bugfix: `stdint.h` & `limits.h` (32-Bit Limits, Macro Conversion, `limits.h`)
* **Problem:**
  1. `UINT32_MAX` in `stdint.h` and `ULONG_MAX` in `limits.c` are defined as `4194304` ($2^{22}$)! Correct value is `4294967295UL` ($2^{32}-1$).
  2. `INT32_MIN` and `LONG_MIN` are defined as `-2097152` ($-2^{21}$), while `INT32_MAX` and `LONG_MAX` are defined as `2097151` ($2^{21}-1$)! Correct values are `-2147483648L` and `2147483647L`.
  3. All limits are declared as `const` variables instead of preprocessor `#define` macros, violating ISO C standards and preventing use in constant expressions (`#if`, `switch`, array dimensions).
  4. The standard header `include/limits.h` is completely missing from the include directory.
* **Solution & Optimization:**
  * Correct all 32-bit boundary values to exact 32-bit limits (`4294967295UL`, `2147483647L`, `-2147483648L`).
  * Convert all limit constants in `<stdint.h>` and `<limits.h>` to standard `#define` macros.
  * Create `include/limits.h` with complete ISO C fundamental type limits.

---

### Feature 44: Library Bugfix: `sine.c` (`sin16s_gen2` Precedence) & `c64-tod.c` (AM/PM Masking)
* **Problem:**
  1. `sine.c` line 44: `signed int offs = min + ampl>>1;` suffers from C operator precedence (`+` precedes `>>`). It evaluates as `(min + ampl) >> 1` = `max >> 1`, causing scaled sine generation (`sin16s_gen2`) to calculate wrong midpoints for any non-zero `min`.
  2. `c64-tod.c` line 37: `tod_str()` executes `tod_buffer[0] = '0' + (tod.HOURS >> 4);` without masking bit 7 (AM/PM flag on MOS 6526 CIA). During PM (after 12:00), bit 7 is set ($80), rendering afternoon hours as `"81:00:00"` or `"92:00:00"`.
  3. `stdlib.c` line 53: `bsearch16u()` returns `items - 1` (out-of-bounds before array) when search key is smaller than the first item.
* **Solution & Optimization:**
  * Fix operator precedence in `sine.c`: `signed int offs = min + (ampl >> 1);`.
  * Mask AM/PM bit in `c64-tod.c`: `tod_buffer[0] = '0' + ((tod.HOURS & 0x1f) >> 4);`.
  * Add underflow boundary check in `bsearch16u()` to safely return `items` or `NULL`.

---

### Feature 45: Library Math: Fast 6502 Division & Zero-RAM Square Root (`sqrt16`, `div16u`)
* **Problem:**
  1. `divr16u` in `division.c` implements 16-bit division via naive C bit shifts and branch masks, costing ~1,280 cycles per division.
  2. `sqr.c` allocates 510 bytes of dynamic RAM via `malloc` (colliding with BASIC under `$A000`), and `sqrt()` performs a slow 8-iteration binary search (`bsearch16u`) across 255 words.
  3. `<math.h>` lacks essential 6502 inline math utilities (`min`, `max`, `clamp`, `popcount8`, `clz8`).
* **Solution & Optimization:**
  * Accelerate `divr16u` using native 6502 Carry-chain shifting (`ASL; ROL; ROL; ROL`), cutting division time to ~300 cycles (4x speedup).
  * Implement standard 6502 digit-by-digit binary square root (`sqrt16`) in ~150 cycles with 0 bytes RAM table allocation, eliminating the 510-byte heap table.
  * Populate `<math.h>` with high-performance inline macros/intrinsics for `min`, `max`, `clamp`, and fast bitwise popcount.

---

### Feature 46: Library Math: Trig & Multiplier Optimization (Sine Quadrants, CORDIC)
* **Problem:**
  1. `sine.c` generates sine tables by evaluating each point from $0$ to $2\pi$ with heavy 32-bit Taylor series multiplications.
  2. `atan2.c` (`atan2_16`) uses a software loop shifting 16-bit integers 2 bits at a time, resulting in massive cycle waste in later CORDIC iterations.
  3. `fastmultiply.c` allocates 2,048 bytes of RAM for multiplication tables, duplicating the compact ROM Quarter-Square tables already established in `F-08`.
* **Solution & Optimization:**
  * Exploit quadrant mirror symmetries ($\sin(\pi-x) = \sin(x)$) to compute only $1/4$ of the wave, speeding up table generation by 4x.
  * Provide `F-32` (`__builtin_lut`) constexpr macros to precalculate sine tables directly into ROM at compile time.
  * Optimize CORDIC shifts with byte-swapping when shift $\ge 8$.
  * Redirect `fastmultiply.c` to use the unified `F-08` ROM tables, freeing 2,048 bytes of RAM on the C64.

---

### Feature 47: Library: High-Performance Retro-Game Utilities (PRNG Suite, Multiplexer, Fast `strlen`)
* **Problem:**
  1. `rand()` in `stdlib.c` lacks 0-seed safety (passing `srand(0)` locks Xorshift permanently into an absorbing zero-state). Furthermore, KickC lacks an ultra-fast 8-bit deterministic PRNG (~11 cycles) and a long-period 32-bit PRNG for procedural generation.
  2. `c64-multiplexer.c` hardcodes `PLEX_COUNT = 32`, forcing every game to pay the full insertion-sort cost for 32 sprites in every frame's raster IRQ even when only 12 or 16 sprites are active.
  3. `strlen()` in `string.c` increments two 16-bit pointers on every character, costing ~28 cycles/char instead of an optimal 9 cycles/char 6502 Y-indexed loop.
  4. `print_uchar_decimal()` in `c64-print.c` unnecessarily promotes bytes to 16-bit words and invokes full 16-bit `utoa()`, taking ~400 cycles instead of ~40 cycles via `div10u` (`F-13`).
* **Solution & Optimization:**
  * **Deterministic PRNG Suite:**
    * Implement seed-safety in `srand()` (`if(seed == 0) seed = 1;`).
    * Add 11-cycle Galois 8-bit LFSR `rand8()` / `srand8(seed)` (period 255).
    * Accelerate 16-bit `rand()` with native 6502 assembly (~30 cycles).
    * Add 32-bit `rand32()` / `srand32(seed)` (period 4.29 billion) for procedural world/map generation.
  * **Configurable Multiplexer:** Allow user override of `PLEX_COUNT` via `#ifndef PLEX_COUNT` and add runtime `plex_count` limiter to skip inactive virtual sprites during sorting.
  * **Fast `strlen()`:** Emit Y-indexed 6502 loop (`LDY #$FF; !: INY; LDA (str),Y; BNE !-; TYA`) for strings $\le 255$ characters.
  * **Fast 8-bit Decimal Print:** Direct `print_uchar_decimal()` dispatch using `div10u` (`F-13`).

---

### Feature 48: Library C64: Hardware Drivers (Commodore 1351 Mouse, REU DMA & Raster Sync)
* **Problem:**
  1. KickC provides `cx16-mouse.h` for Commander X16, but completely lacks a mouse driver for Commodore 64 (SID proportional PotX/PotY sampling, CIA1 4066 analog mux, coordinate clamping, and automatic Joystick-fallback).
  2. KickC lacks Commodore RAM Expansion Unit (REU 1700/1750/1764) hardware definitions and DMA transfer routines.
  3. `c64.h` only exposes raw VIC-II registers (`$D011`/`$D012`) without safe, high-level raster wait routines (handling 9-bit raster counter bit 7).
* **Solution & Optimization:**
  * **`<c64-mouse.h>` & `c64-mouse.c`:**
    * Full 1351 proportional mouse driver via SID PotX/PotY (`$D419`/`$D41A`) and CIA1 analog mux.
    * Automatic detection and seamless fallback to Joystick 1/2 mouse emulation when no 1351 is plugged in.
    * Clamped pixel coordinates ($320\times 200$), tile coordinates ($40\times 25$), click/drag detection, and 64-byte 1351 pointer sprite.
  * **`<reu.h>`:**
    * Hardware register definitions for Commodore 17xx REU (`$DF00..$DF0A`).
    * Hardware presence detection (`reu_is_present()`).
    * High-speed 1 MB/s DMA primitives: `REU_DMA_STASH`, `REU_DMA_FETCH`, `REU_DMA_SWAP`.
  * **`<c64-raster.h>`:**
    * Clean macros/inline functions for tearing-free vertical blank waiting (`wait_vblank()`) and 9-bit raster-line waiting (`wait_raster(line)`).

---

### Feature 49: Library C64: Asset & Graphics Pipeline (Universal ZX02 Decompressor, Fading)
* **Problem:**
  1. KickC provides no built-in compression or decompression libraries for embedded binary assets.
  2. Screen transitions in `conio-c64.c` are limited to instant clears (`clrscr()`), lacking smooth perceptual luminance fades.
* **Solution & Optimization:**
  * **`<zx02.h>` & `zx02.c`:**
    * Universal, ultra-compact (~110 bytes 6502 machine code) streaming decompressor for Einar Saukas' ZX02 format.
    * Decompresses from Cartridge-ROM or RAM directly into any destination address ($0400..$FFFF, including transparent matching under Kernal).
  * **`<c64-fade.h>` & `c64-fade.c`:**
    * Perceptual C64 palette luminance stepping tables.
    * Smooth full-screen Color-RAM and Screen-RAM fading (`screen_fade_out()`, `screen_fade_in()`).







---

### Feature 50: Library C64: High-Speed Disk I/O Suite (Hardware Probing, Turbo Read/Write, ELoad)
* **Problem:**
  Standard Commodore Kernal serial I/O (cbm_k_chrout, cbm_k_chrin, cbm_k_open) performs bit-banging over the slow IEC serial bus, requiring ~25–35 seconds to transfer an 8 KB data block (e.g. savegames, level data, music files). Furthermore, scene fastloaders typically focus exclusively on read operations (loaders for demos) and crash on microcontroller-based storage devices (SD2IEC, IEC-ATA) by attempting to execute drive-side 6502 code via M-E.
* **Solution & Optimization:**
  * Implement MIT-licensed high-speed disk I/O suite based on Lasse Öörni's proven c64loader (Covert Bitops):
    * **Drive Probing (disk_probe()):** Reliably detects connected drive type: standard 1541/1571/Pi1541/Ultimate II (CPU-emulating drives with drive RAM), SD2IEC (microcontroller-based FAT drives), or generic IEC devices.
    * **Turbo Read (disk_fast_load()):** High-speed block loading with up to 25x standard Kernal speed.
    * **Turbo Write (disk_fast_save()):** High-speed block writing (~1–2 seconds for 8 KB) using custom drive-code on 1541 and ELoad protocol on SD2IEC.
    * **Transparent Kernal Fallback:** Graceful degradation to standard Kernal I/O when connected to unsupported or virtual network drives.
  * Packaged as clean C interface in <c64-fastio.h> and c64-fastio.c with zero resident footprint requirements (can execute from RAM or overlays).
  * **Licensing:** Permissive MIT License (Lasse Öörni / Covert Bitops attribution preserved in source header and documentation).



---

### Feature 51: Library: Ultra-Fast Run-Length Encoding (RLE) Compression Suite (<rle.h>)
* **Problem:**
  High-ratio crunchers like ZX02, PuCrunch, or Exomizer employ optimal dynamic programming parsers that require megabytes of host RAM and millions of instructions, making real-time on-device compression completely intractable on a 1 MHz NMOS 6502. However, games frequently require fast real-time on-device compression (e.g. savegame serialization, procedural world staging, network packets, delta buffers) directly before writing to slow IEC serial disk drives or serial modems.
* **Solution & Optimization:**
  * Implement an ultra-fast, symmetric 6502 RLE suite in `<rle.h>` and `rle.c`:
    * `rle_pack(const void* src, void* dest, uint16_t src_len)`: Compresses 8 KB of sparse data in ~15–25 milliseconds (< 2 video frames) with zero heap/RAM allocation (utilizing only 2 ZP pointers and a run counter).
    * `rle_unpack(const void* src, void* dest, uint16_t max_dest_len)`: Unpacks data in ~8–12 milliseconds.
    * Uses a standardized PackBits / byte-run format (flagging repeated byte runs vs. raw literal spans), achieving 60–80% of ZX02's compression ratio on sparse state tables at 100,000x faster compression speed.

---

## Quality Assurance & Automated Regression Test Suites (QA-01 to QA-03)

### QA-01: Modern C Standards Hardening (C11/C23/C2Y)
* **Goal:** Permanent regression test coverage in `src/test/kc/` and JUnit `TestProgramsFast.java`.
* **Scope:** `#embed` (F-17), `enum : uint8_t` (F-18), `countof()` (F-19), Named loops `break/continue label;` (F-20), `static_assert` (F-21), `_Generic` (F-22), Digit separators & `nullptr`/`{}` (F-23), `if`/`switch` with init (F-24), C11 anonymous unions (F-02).
* **Test Focus:** Boundary values (0, 255, empty assets, multidimensional arrays), nested scopes, and negative tests verifying that syntax violations strictly fail compilation with clear `CompileError`. (Issue #52).

---

### QA-02: 6502 Low-Level & ABI Hardening
* **Goal:** Consolidate low-level 6502 code generation optimizations into verifiable automated regression tests.
* **Scope:** Leaf-function register calling convention (F-26), Auto Carry-flag boolean returns (F-25), 8.8 fixed-point arithmetic (F-27), Pre-indexed ZP `($zp,X)` (F-31), Cycle-exact delays (F-30), Pass-5 bit-tests (F-34), Byte-skipping via `.byte $2c` (F-35).
* **Test Focus:** Full 3-parameter passing (A, X, Y), safe ZP fallbacks upon register clobbering, relational operators (`<`, `<=`, `>`, `>=`, `==`, `!=`) in Carry ABI, register/flag preservation during delays, and bit-exact execution in VICE test harness. (Issue #53).

---

### QA-03: Arithmetic & Memory Safety Hardening
* **Goal:** Verify mathematical precision across exhaustive domains and ensure formal memory model safety rules are strictly enforced.
* **Scope:** Reciprocal constant division & modulo 0..255 (F-13), Quarter-square multiplication exhaustive (F-08), 8x8->16 constant mul (F-16), Segment type ROM safety & Cross-bank checks (F-00), StackRam `$0100-$01AF` overflow protection (F-29), ZP coalescing (F-06).
* **Test Focus:** Exhaustive domain verification (0..255 for all divisors), negative compile tests for ROM symbol writes and direct cross-bank calls without trampolines, and hardware stack bounds verification. (Issue #54).

---

# Milestone v0.11.0: Advanced Code Size Optimization, Bytecode & Structural Layout

### Feature 52: Optimizer: Value Range Propagation & Automatic 16-to-8 Bit Expression Narrowing
* **Problem:**
  C type promotion rules frequently widen byte-level operations (e.g. `(a + b) >> 1` or array index arithmetic) into 16-bit intermediate SSA variables. On the 6502, this leads to unnecessary 16-bit Zero-Page register allocation, multi-byte addition loops, or compiler errors when combined shift/downcast fragments are absent.
* **Solution & Optimization:**
  * Implement static value range propagation in Pass 2 (SSA Optimization) tracking interval bounds $[min, max]$ through expression trees.
  * When subexpression bounds are proven to fit within $[0, 255]$ (or $[-128, 127]$ for signed bytes), narrow the SSA operation and target type to 8-bit.
  * Transparently eliminates 16-bit register allocations and eliminates manual `(uint8_t)` casting requirements. (Issue #56).

---

### Feature 53: Diagnostics: Source-Level Code Size Profiler & Memory Accounting (.csz / JSON)
* **Problem:**
  Developers optimizing memory on the 6502 struggle to identify which specific high-level C statements expand into disproportionately large machine code sequences without manually inspecting disassembly.
* **Solution & Optimization:**
  * Post-codegen analysis in Pass 5 aggregating machine instruction lengths mapped to source code locations.
  * Emits detailed line-by-line memory accounting reports (`.csz` text format and structured JSON).
  * Generates top-20 ranking of largest functions and hot spots to accelerate memory footprint optimization. (Issue #57).

---

### Feature 54: Language: Selective Loop Unroll Pragmas (#pragma unroll(N) / __unroll)
* **Problem:**
  Global loop unroll limits (`-Ounroll`) apply across all loops indiscriminately. Enabling high unroll factors causes code size explosions across the whole program, while low factors prevent full unrolling of small time-critical raster routines.
* **Solution & Optimization:**
  * Add `#pragma unroll(N)`, `#pragma unroll full`, and `#pragma nounroll` syntax in Pass 0.
  * Allows fine-grained unroll directives to attach directly to `for`, `while`, and `do-while` loops, overriding global heuristics. (Issue #58).

---

### Feature 55: Optimizer Bugfix: Transitive Link-Time Dead Data & Table Elimination
* **Problem:**
  `PassNEliminateUnusedVars` removes initially unused variables, but data tables referenced by procedures that are subsequently eliminated by `Pass2EliminateUnreachableProcedures` or inlining passes remain stranded in data segments. This causes phantom memory overflows in restricted memory regions.
* **Solution & Optimization:**
  * Implement whole-program reachability traversal starting from confirmed entry points (`main`, hardware interrupt vectors).
  * Purge any global data array, table, or constant not transitively reachable through active execution paths prior to Pass 4 code generation. (Issue #59).

---

### Feature 56: Language: Automatic Struct-Packing & Field Reordering (__reorder / #pragma struct_pack)
* **Problem:**
  Standard C struct member order often introduces unaligned fields and prevents 8-bit indexed addressing without crossing page boundaries.
* **Solution & Optimization:**
  * Implement opt-in directive `__reorder` and `#pragma struct_pack(reorder)`.
  * Automatically clusters byte-sized fields together and word-sized fields together.
  * Eliminates padding gaps and allows single-instruction indexed addressing (`LDA struct_bytes,X`) on 6502. (Issue #60).

---

### Feature 57: Optimizer: Pass-5 Peephole Tail-Merging & Cross-Jumping
* **Problem:**
  Many procedures end with identical register restoration or status clearing sequences (e.g. `PLA; TAY; PLA; TAX; PLA; RTI` or `LDA #0; RTS`), duplicating 6–12 bytes per routine across hundreds of functions.
* **Solution & Optimization:**
  * Analyze procedure epilogues in Pass 5 before emitting assembly.
  * When an identical sequence of instructions exists at another function's exit, replace the redundant epilogue with a direct branch or jump (`jmp shared_epilogue`).
  * Reclaims 150–300+ bytes of resident code across larger programs at negligible cycle cost. (Issue #61).

---

### Feature 58: Architecture: Selective 6502 Bytecode Virtual Machine (-bc / #pragma bytecode)
* **Problem:**
  Complex control logic, dialogue trees, game menus, and scripted events consume vast amounts of RAM when compiled to native 6502 instructions, quickly exceeding 64 KB memory bounds.
* **Solution & Optimization:**
  * Provide `#pragma bytecode` / `__bytecode` procedure annotation and `-bc` compiler flag.
  * Emit compact 1-byte opcodes for designated procedures, executed by a minimal embedded ~250-byte 6502 interpreter loop.
  * Reduces compiled code footprint by 40–50% for complex non-performance-critical routines. (Issue #62).


---

### Feature 59: Optimizer: Cross-Scope Constant Storage Elimination & Immediate Inlining
* **Problem:**
  In `Pass4CodeGeneration.java`, `useLabelForConst()` forces scalar `const` variables (e.g. `const uint8_t MAX_ITEMS = 8;`) to be emitted as assembly memory labels or memory bytes whenever they are referenced across different procedure scopes. Because `Pass2ConstantInlining` currently only processes unnamed intermediate compiler temporaries, named user constants continue to consume memory in data segments even when their address is never taken via `&`.
* **Solution & Optimization:**
  * Extend constant inlining across scopes: When a named scalar `const` variable is never address-taken anywhere in the program, inline its literal value directly at every usage site as an immediate operand (`#imm`).
  * Suppress assembly `.label` and storage byte allocation in Pass 4 for inlined scalar constants.
  * Frees data segment RAM/ROM across projects with extensive configuration constants. (Issue #63).

---

### Feature 60: Optimizer: If-to-LUT Data-Flow Synthesizer (Branch Cascade to Indexed Table)
* **Problem:**
  Sequences assigning values based on a small contiguous index (e.g. `if(dir==0) dx=0; else if(dir==1) dx=1; else if(dir==2) dx=0; else if(dir==3) dx=-1;`) currently generate chains of comparison and branch instructions (`CMP #val; BNE`), taking up to 25+ cycles and 20+ bytes of code. While `F-01` addresses control-flow branching via RTS jump-tables, data-flow value mappings remain unoptimized.
* **Solution & Optimization:**
  * Detect dense linear value assignment chains in Pass 2 / Pass 5 where all branches assign constant literals to the same destination variable.
  * Synthesize an anonymous read-only lookup table in ROM/segment and replace the branch cascade with a single indexed load (`LDX idx; LDA lut,X; STA dest`).
  * Provide explicit control via `#pragma branch_to_lut` / `__lut` directive as well as global optimization flag `-Obranch-to-lut`.
  * Execution drops from ~25 cycles to 7 cycles, and code shrinks from 24 bytes to 5 bytes. (Issue #64).
