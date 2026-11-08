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

## 2. Feature Matrix (Strictly Sequenced F-00 to F-24)

| ID | Category | Feature Description | Origin / Standard | Status |
| :---: | :--- | :--- | :---: | :---: |
| **F-00** | Architecture | Segment Type Classification (`#pragma segment_type`) & Cross-Bank Verification | KickC Ext | ✅ Completed |
| **F-01** | Codegen | Split Jump-Tables for dense `switch` via 12-cycle RTS dispatch | 6502 Idiom | ✅ Completed |
| **F-02** | Types | C11 Anonymous Unions & 2–4 Byte Assignment Loop-Unrolling | C11 | ⚪ Planned |
| **F-03** | Intrinsics | Atomic Status Intrinsics (`__builtin_bit7/6`, `SEC`, `CLC`, etc.) | KickC Ext | ✅ Completed |
| **F-04** | Codegen | Zero-Syntax Auto Pointer-to-SMC (Self-Modifying Code in RAM) | 6502 Idiom | ⚪ Planned |
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
| **F-16** | Arithmetics | Compact 8x8->16 Multiplication without 16-bit Zero-Extension | Pass 2 SSA | ⚪ Planned |
| **F-17** | Preprocessor | `#embed` Directive for Direct Binary Asset Embedding | C23 | ⚪ Planned |
| **F-18** | Types | Typed 8-Bit Enumerations (`enum : uint8_t`) | C23 | ⚪ Planned |
| **F-19** | Operators | Compile-Time Array Length Operator `countof()` | C2Y (C29) | ⚪ Planned |
| **F-20** | Controlflow | Named Loops: `break label;` & `continue label;` | C2Y (C29) | ⚪ Planned |
| **F-21** | Safety | Compile-Time Assertions (`static_assert` / `_Static_assert`) | C11 / C23 | ⚪ Planned |
| **F-22** | Metaprog | `_Generic` Type-Dispatch for Polymorphic 8/16-Bit Macros | C11 | ⚪ Planned |
| **F-23** | Lexer | Digit Separators (`0b1100_0011`, `1_000`) & C23 Keywords (`nullptr`, `{}`) | C23 | ⚪ Planned |
| **F-24** | Controlflow | `if`-Statements with Initializers for Strict Scope Limitation | C2Y (C29) | ⚪ Planned |

---

## 3. Detailed Specification: Planned Low-Level 6502 Optimizations

### Feature 02: C11 Anonymous Unions & 2–4 Byte Assignment Loop-Unrolling
* **Goal:** Direct 16/32-bit field aliasing without intermediate named structs, and unrolling small `struct` assignments into direct `LDA`/`STA` pairs rather than emitting overhead-heavy memory copy loops.

### Feature 04: Zero-Syntax Pointer-to-SMC Uplift (Self-Modifying Code)
* **Goal:** For code marked in `RAM_RESIDENT`, detect hot sequential pointer loops and transform indirect indexed addressing (`LDA ($zp),Y`) into direct self-modifying immediate operands (`LDA $xxxx`). Saves 2 cycles per byte and frees Zero-Page registers.

### Feature 09: 8-Bit Indexing for $\le 128$ Word-Arrays & `__lohi` Split Arrays
* **Goal:** When indexing 16-bit word arrays with $\le 128$ elements, avoid 16-bit pointer arithmetic. Generate `ASL; TAX; LDA tab,X; LDA tab+1,X` (12 cycles) or split low/high arrays `LDA tab_lo,X; LDY tab_hi,X` (8 cycles).

### Feature 10: Struct-of-Arrays (`__soa`) Automatic Flattening
* **Goal:** Automatically transpose arrays of structures (`AOS`) into parallel arrays of primitive fields (`SOA`), enabling single-instruction indexed addressing (`LDA player_x,X`) rather than pointer multiplication.

### Feature 11: 16-Bit Register Pair `AX` for Return Values & Parameters
* **Goal:** Allow functions returning 16-bit values to pass the low byte in `A` and the high byte in `X`, avoiding Zero-Page allocation for function returns and leaf parameters.

### Feature 16: Compact 8x8->16 Multiplication without 16-Bit Zero Extension
* **Goal:** For multiplications of `uint8_t` by constants where the result fits in 16-bit, accumulate the calculation in 8-bit shifts and additions before promoting to 16-bit, halving code size and Zero-Page register consumption.

---

## 4. Detailed Specification: Modern C Standards Track (C11 / C23 / C2Y)

### Feature 17: `#embed` Direct Binary Asset Embedding (C23)
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
  * Eliminates external asset compilation scripts.

---

### Feature 18: Typed 8-Bit Enumerations `enum : uint8_t` (C23)
* **Problem:** In traditional C, `enum` defaults to at least `int` (16-bit or 32-bit). On 6502 systems, 16-bit enumeration variables waste Zero-Page storage and require multi-cycle instructions.
* **Syntax:**
  ```c
  enum ScreenMode : uint8_t {
      MODE_TEXT,
      MODE_BITMAP,
      MODE_EXTENDED
  };
  ```
* **Compiler Integration:**
  * Pass 1 (`CParserVisitor` & `SymbolType`): Enforces `BYTE_UNSIGNED` as the underlying representation.
  * Guarantees 1-byte storage, zero integer-promotion overhead, and full compiler type safety.

---

### Feature 19: Compile-Time Array Length Operator `countof()` (C2Y / C29)
* **Problem:** Calculating array length via `sizeof(arr) / sizeof(arr[0])` is verbose, repetitive, and error-prone when array element types change.
* **Syntax:**
  ```c
  const uint8_t lookup_table[] = { 10, 20, 30, 40 };
  for (uint8_t i = 0; i < countof(lookup_table); ++i) {
      process(lookup_table[i]);
  }
  ```
* **Compiler Integration:**
  * Resolved entirely in Pass 0/1 constant evaluation as an immediate integer literal.
  * Zero runtime cycles, zero code size overhead.

---

### Feature 20: Named Loops: `break label;` & `continue label;` (C2Y / C29)
* **Problem:** Breaking out of nested loops in C traditionally required either `goto` or auxiliary boolean flags (e.g. `bool abort = true; if (abort) break;`). On 6502, auxiliary flags consume valuable Zero-Page registers and CPU cycles.
* **Syntax:**
  ```c
  outer: for (uint8_t y = 0; y < 25; ++y) {
      for (uint8_t x = 0; x < 40; ++x) {
          if (screen[y][x] == 0) {
              break outer; // Directly breaks out of both loops
          }
      }
  }
  ```
* **Compiler Integration:**
  * Pass 0/1 associates the label with the outer iteration statement.
  * Pass 3 (Statement to SSA Control Flow Graph) directly routes the break edge to the exit block of the designated loop.
  * Generates a single conditional or unconditional 6502 branch (`BNE`/`BEQ` or `JMP`) without intermediate flag variables.

---

### Feature 21: Compile-Time Assertions `static_assert` (C11 / C23)
* **Problem:** Retro systems have strict memory window constraints (e.g. structures fitting within 256-byte boundaries or bank overlays). Structural overflows were often only detected via runtime crashes.
* **Syntax:**
  ```c
  static_assert(sizeof(struct ActorState) <= 64, "ActorState exceeds allocated memory block!");
  ```
* **Compiler Integration:**
  * Evaluated during Pass 0/1 constant resolution.
  * Emits clear compiler errors with source line numbers during compilation if the condition is false.

---

### Feature 22: `_Generic` Compile-Time Type Dispatch (C11)
* **Problem:** On 6502, functions and assembly fragments are strictly specialized into 8-bit and 16-bit variants (e.g. `abs8` vs `abs16`, `fast_mul8u` vs `mul16u`). Developers had to call type-specific function names manually.
* **Syntax:**
  ```c
  #define abs(x) _Generic((x), \
      int8_t: abs8(x), \
      int16_t: abs16(x) \
  )
  ```
* **Compiler Integration:**
  * Evaluated at the macro/AST level in Pass 0 based on the resolved argument type.
  * Enables clean, unified APIs that map directly to the optimal assembly fragment without runtime overhead.

---

### Feature 23: Digit Separators (`0b1100_0011`, `1_000`) & C23 Keywords
* **Problem:** Hardware registers on retro platforms (VIC-II, CIAs, SID) are bitmask-driven. Large binary and hex literals without grouping are prone to off-by-one bit errors.
* **Syntax:**
  ```c
  uint8_t vic_control = 0b0001_1000;
  uint16_t raster_line = 1_000;
  struct Entity e = {}; // C23 empty zero-initialization
  void* ptr = nullptr;  // C23 typed null pointer
  ```
* **Compiler Integration:**
  * Updated `KickCLexer.g4` to allow underscores inside binary, decimal, and hexadecimal literal tokens.
  * Native recognition of `nullptr`, `true`, `false`, and empty struct initialization `{}`.

---

### Feature 24: `if`-Statements with Initializers (C2Y / C29)
* **Problem:** Declaring temporary variables outside an `if` statement prolongs their live range, preventing KickC's SSA coalescing pass from reusing the Zero-Page register for unrelated code.
* **Syntax:**
  ```c
  if (uint8_t status = read_hardware_status(); status != 0) {
      handle_status(status);
  }
  // 'status' is immediately out of scope here
  ```
* **Compiler Integration:**
  * Syntactic scope of the declared variable is strictly confined to the `if`/`else` body in Pass 1.
  * Shortens the live range in Pass 4, allowing immediate Zero-Page register reuse.
