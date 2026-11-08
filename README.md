# KickC Enhanced 0.9.0 – Optimizing C-compiler for 6502 platforms

> **Fork Notice:** This repository is an enhanced fork of [KickC](https://gitlab.com/camelot/kickc) by **Jesper Balman Gravgaard**, maintained by **André Reichelt**.

## Enhancements & Additions in this Fork

* **267 New / Repaired MOS6502 ASM Fragments:**  
  Greatly extends the compiler's native fragment synthesis and unlocks full idiomatic C patterns without defensive workarounds:
  * **Struct-Pointer Member Decrements & Comparisons:** Native support for `if (items[i].count > 0) items[i].count--;` across all register weights and memory pointers (`pbum1_derefidx_vbuc1_*`, `pbuz1_derefidx_vbuc1_*`).
  * **Complete Boolean Logical-NOT (`!`):** Synthesizes optimal zero-branch 3-instruction toggles (`lda`, `eor #1`, `sta`) for zero-page (`vboz1`), memory (`vbom1`), and struct dereferences (`vboz1=_not__deref_pboc1`).
  * **32-Bit (Dword) Zero Comparisons:** Direct branch tests for `uint32_t` against zero (`vduz1_gt_0_then_la1`, `vduz1_le_0_then_la1`).
  * **16-Bit Word Byte-Aligned Shifts:** `wordArray[i] = ((uint16_t)byteVal) << 8;` directly lowers into 4-instruction byte moves without 8x `asl`.
  * **Word Pointer & Memory Offset Arithmetic:** Deeply nested word/byte pointer index operations, including memory minus byte offsets, word-pointer additions/subtractions, and multi-level indirection.
  * **Complementary Signed/Unsigned Casts & Pointer Arithmetic:** Complete coverage for byte/word pointer increments, decrements, comparisons, and signed conversions (`snumber`, `sword`).
  * **Dedicated Undocumented NMOS-6510 Opcode Fragments:** Native zero-page, absolute, and indirect-Y fragments (`LAX`, `SAX` zero-page `vbuz1=vbuaa_band_vbuxx`).

* **NMOS-6510 Unintended/Illegal Opcode Optimization (`Pass5IllegalOpcodeOptimization`):**  
  Active when targeting CPUs with undocumented opcode support (`mos6502x` / `rom6502x`, feature flag `TargetCpu.Feature.MOS6502_UNODC`). Automatically synthesizes and folds stable, deterministic green opcodes (documented in *No More Secrets*):
  * **`LAX <mem>`:** Automatically folds `LDA <mem>; TAX` and `LDX <mem>; TXA` for Zero Page, Absolute, and Indirect-Y addressing modes.
  * **`SAX <mem>`:** Added native Zero-Page fragment (`vbuz1=vbuaa_band_vbuxx.asm`) storing `A & X` directly into zero-page cells in 3 cycles.
  * **`ALR #{imm}`:** Collapses `AND #{imm}; LSR A` into a single 2-byte, 2-cycle instruction.
  * **`DCP <mem>` & `ISC <mem>`:** Folds decrement/increment memory combined with compare/subtract into single R-M-W opcodes.
  * **Accumulator Shift Folding & Zero-Extension:** Direct lowering of 16-to-8 bit right-shifts by 2 (`lsr; lsr`) via the accumulator, and redundant 16-bit zero-extension before shift removal.
  * **Zero/Sign-Flag Predication & Redundant Compare Elimination:** Automatically removes redundant `CMP #0`, `CPX #0`, `CPY #0` before conditional branches when preceding instructions already define Z and N flags.
  * **Redundant Load Elimination:** Removes dead `LDA <mem>` after `DEC/INC <mem>` before branches when fallthrough paths overwrite the accumulator.
  * **Micro-Peepholes:** Eliminates redundant `EOR #0` (e.g. from boolean casts) and redundant register roundtrips (`TAX; TXA`, `TXA; TAX`, `TAY; TYA`, `TYA; TAY`).
  * **Strict Safety:** Excludes unstable/analog opcodes (`SHA`, `SHX`, `SHY`, `TAS`, `ANE`, `LAX #imm`) to guarantee 100% hardware and VIC-II DMA stability.

* **Tail Call & Return Trampoline Optimization (`Pass5TailCallOptimization` & `Pass5FixLongBranches`):**  
  * Automatically converts function tail calls (`JSR <target>` followed immediately by `RTS` or labeled `__breturn: RTS`) into `JMP <target>`.
  * **Return Trampoline Relaxation:** Detects conditional long branch trampolines jumping to function return points (`rts` / `__breturn`) and replaces them directly with inverted local branches and return instructions, saving code size and CPU cycles across large programs.

* **Dead Store & Dead Load-and-Store Pair Elimination with `CanonicalResolver` (`Pass5DeadStoreElimination`):**  
  * Eliminates dead stores to procedure-local SSA temporaries and unused function return values in linear $O(1)$ time.
  * **Recursive `CanonicalResolver`:** Resolves all scope-local and global `.label Name = Target` definitions, symbol alias chains, and byte offsets (`+1`, `+2`, etc.) to canonical keys and physical addresses, ensuring variables accessed under different SSA/alias names are safely recognized as live.
  * **Register A & Flag Safety:** Employs precise `readsRegisterA` tracking to preserve necessary loads when subsequent instructions (e.g. `sta LiveVar`) consume register A, only removing loads when A and status flags are provably overwritten without prior reads.

* **Static Register Tracking Across Anonymous Forward Labels (`AsmProgramStaticRegisterValues`):**  
  * Tracks static values of registers `A`, `X`, and `Y` across local forward-only anonymous labels (`!:`) when skipped intermediate instructions (such as 16-bit pointer high-byte increments `inc ptr / bne !+ / inc ptr+1 / !:`) do not touch the tracked registers. Eliminates redundant loads in inner loops.

* **Sequential Cross-Chunk Peephole Engine:**  
  Overcomes KickC's historical single-statement `AsmChunk` boundary limitation by using a sequential `LineRef` pipeline across chunk borders in Pass 5.

* **Custom Character Encodings & Literal Suffixes:**  
  * **`#pragma charmap(custom_name, base_encoding, "mapping_rules")`:** Allows defining project-specific character encodings on the fly (e.g. language-specific umlauts, special symbols, custom font layouts) without compiler source modifications.
  * **Encoding Literal Suffixes:** Direct literal suffixes for individual strings and characters (`"Hello"ascii`, `"Grüße"german`, `'ä'german`, `"Buffer"german_z` for non-zero terminated strings).

* **Cross-Segment Data Integrity & String Pooling:**  
  * Preserves declared data segments (`#pragma data_seg(...)`) for inline string constants rather than forcing them into the default data segment.
  * Corrected phi-value propagation to default successors in `rewriteLogicAnd`.

* **Word Constant Multiplication Fix:**  
  Fixed and optimized constant multiplication for 16-bit word targets (`* 24`, `* 40`, `* 320`) via optimized `_mulu16_*` and `_muls16_*` synthesis templates, promoting byte operands automatically when multiplying with constants > 255.

* **Multi-Core Architecture & Compiler Performance Optimizations:**  
  Substantially reduced compilation times for large multi-bank C64 codebases (from >20 minutes down to ~1 minute):
  * **Parallel Dominator Tree:** Dynamic multi-core parallel dominator calculation across all available CPU cores/threads and O(1) graph predecessor indexing.
  * **Multi-Core Pass 4 Uplift:** Multi-threaded register uplift synthesis across available CPU cores.
  * **O(1) Fast Lookups:** Fast Statement-to-Block lookup (`StatementInfos.getBlock`), precomputed `LiveRangeInterference` overlap matrix.
  * **Optimized SSA & Alias Passes:** Variable-to-set indexing and fast phi check in `Pass2AliasElimination`, O(1) VarAssignments indexing, memoized `LoopDepthAnalysis`.
  * **Long Branch Guarding:** Guarded expensive ASM/symbol string conversions in `Pass5FixLongBranches` when verbose logging is inactive.

* **Modern C Standards & Systems Programming Extensions:**  
  Selectively incorporates high-leverage modern C language features and 6502 low-level extensions with zero runtime overhead designed to solve acute memory and performance challenges in 8-bit systems:
  * **Struct-of-Arrays Flattening (`__soa`):** Automatically transforms arrays of structures up to 256 elements into parallel column arrays in memory (`__soa struct Entity entities[32]`). Accesses to struct members (`entities[i].field`, writes, compound assignments, and pre/post increments) compile directly to 4-cycle absolute indexed addressing (`lda entities + Offset, x`) eliminating dynamic multiplication with the structure size, Zero-Page pointer overhead, and runtime indirection. Supports constant struct list initializers, retains natural `sizeof()` ($N \times \text{sizeof}(\text{struct})$) and `countof()` ($N$), and prevents illegal raw struct pointer decays.
  * **Split Low/High Word Arrays (`__lohi`):** Declares 16-bit word arrays up to 256 elements split into parallel low- and high-byte tables in memory (`__lohi uint16_t table[200]`). Accesses compile directly to 8-cycle indexed absolute loads and stores (`lda table,x; lda table+N,x`) without index doubling (`ASL`), 16-bit pointer math, or index register clobbering. Supports constant-initialized tables, dynamic reads, writes, compound assignments, pre/post increments, and preserves natural `sizeof()` ($2 \times N$) and `countof()` ($N$).
  * **Packed 4-Bit Nibble Arrays (`uint4_t` / `__nibble`):** First-class 4-bit scalar integer type with automatic pairwise packing into bytes for array allocations (`(N + 1) / 2` bytes allocated), halving RAM usage for nybble buffers (such as Color-RAM caches). Features optimal compiler lowering for reads, writes, and compound assignments with constant and variable indexing, as well as exact `sizeof()` (byte capacity) and `countof()` (element count).
  * **ISO-C Bitfields in Structs (`:` Syntax):** Full support for ISO-C bitfields in structures (`uint8_t flag : 1;`, signed and unsigned) with automatic container packing into integer units, branchless sign extension, and optimized bit masking during reads, writes, and compound assignments.
  * **Compile-Time Array Size Operator `countof()` (C2Y N3169):** Evaluates exact element counts for arrays, types, structs, and string literals at compile time without runtime cycles or division (`countof(table)`). Formally rejects non-array pointer types to prevent subtle bugs.
  * **Compile-Time Assertions `static_assert` & `_Static_assert` (C11 / C23):** Validates memory layout constraints, structure sizes, and constant invariants at compile time with 0-byte runtime overhead (`static_assert(sizeof(struct Actor) <= 64, "overflow")` and single-argument `static_assert(BUF_SIZE > 0)`).
  * **Named Loops (`break label;` & `continue label;` — C2Y N3355):** Prefix loops with a label (`outer: for(...)`) and jump out or continue directly from deeply nested loops or `switch` statements without auxiliary flags or `goto`.
  * **`if`- and `switch`-Statements with Initializers (C2Y / C++17):** Declarations inside the condition header (`if (uint8_t d = get_dist(); d < 10)`) are strictly block-scoped, allowing KickC's SSA live-range analyzer to immediately reuse Zero-Page registers for subsequent code.
  * **Typed 8-Bit Enumerations (`enum : uint8_t` — C23):** Enforces 1-byte storage for enums, eliminating unwanted 16-bit integer promotion and saving hundreds of cycles in state machines and flag checks.
  * **Digit Separators & C23 Literals (`0b1100_0011`, `1_000`, `nullptr`, `{}`):** Allows underscores in binary, hex, and decimal literals for error-free hardware bitmask notation, plus standard C23 `nullptr` and empty zero-initializers `{}` for structs and arrays.
---

## About KickC

KickC is a C-compiler for 6502-based platforms creating optimized and readable assembler code.

The language is 95% standard C99 with a few limitations, and a few extensions to ensure an optimal fit for creating 6502 assembler code.

The KickC-compiler includes all necessary linker and header files to make it easy to create and test binaries for the following 6502-based platforms out-of-the-box:
- Commodore VIC 20
- Commodore 64
- Commodore Plus/4 (Commodore 16 ,  Commodore 116)
- Atari 2600
- Atari XL/XE 
- Nintendo NES
- MEGA65
- Commander X16

KickC uses the very versatile [Kick Assembler](http://theweb.dk/KickAssembler). The KickC Compiler produces assembler code for the MOS Technology 6502 processor family. Specifically the compiler supports 6502, 65C02, 65CE02 and 45GS02 CPUs.

## Resources

* [GitHub Repository (KickC Enhanced)](https://github.com/AndreR/kickc-enhanced) maintained by André Reichelt
* [Upstream Repository (GitLab)](https://gitlab.com/camelot/kickc) by Jesper Balman Gravgaard
* [Download](https://gitlab.com/camelot/kickc/-/releases) the newest Upstream Release 
* [Download](https://s3.sto3.safedc.net/kickc-nightly/kickc-release.zip) an upstream nightly build 
* [Read](https://docs.google.com/document/d/1JE-Lt5apM-g4tZN3LS4TDbPKYgXuBz294enS9Oc4HXM/edit?usp=sharing) the Reference Manual
* [See](https://camelot.gitlab.io/kickc/files.html) the included C standard library
* [Follow](https://gitlab.com/camelot/kickc/issues) the features being developed
* [Discuss](https://www.facebook.com/groups/302286200587943/) the compiler and receive news on facebook
* [Chat](https://discord.gg/q6TFMZvfg5) on discord
