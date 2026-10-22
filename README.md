# KickC Enhanced – Optimizing C-compiler for 6502 platforms

> **Fork Notice:** This repository is an enhanced fork of [KickC](https://gitlab.com/camelot/kickc) by **Jesper Balman Gravgaard**, maintained by **André Reichelt**.

## Enhancements & Additions in this Fork

* **172 New / Repaired MOS6502 ASM Fragments:**  
  Greatly extends the compiler's native fragment synthesis and unlocks full idiomatic C patterns without defensive workarounds:
  * **Struct-Pointer Member Decrements & Comparisons:** Native support for `if (items[i].count > 0) items[i].count--;` across all register weights and memory pointers (`pbum1_derefidx_vbuc1_*`, `pbuz1_derefidx_vbuc1_*`).
  * **Complete Boolean Logical-NOT (`!`):** Synthesizes optimal zero-branch 3-instruction toggles (`lda`, `eor #1`, `sta`) for zero-page (`vboz1`), memory (`vbom1`), and struct dereferences (`vboz1=_not__deref_pboc1`).
  * **32-Bit (Dword) Zero Comparisons:** Direct branch tests for `uint32_t` against zero (`vduz1_gt_0_then_la1`, `vduz1_le_0_then_la1`).
  * **16-Bit Word Byte-Aligned Shifts:** `wordArray[i] = ((uint16_t)byteVal) << 8;` directly lowers into 4-instruction byte moves without 8x `asl`.
  * **Missing Signed/Unsigned Casts & Pointer Decrements:** Complementary comparison and pointer decrements for smooth assembly code generation.

* **Word Constant Multiplication Fix:**  
  Fixed and optimized constant multiplication for 16-bit word targets (`* 24`, `* 40`, `* 320`) via optimized `_mulu16_*` and `_muls16_*` synthesis templates.

* **Compiler Performance & Pass Optimizations:**  
  Substantially reduced compilation times for large multi-bank C64 codebases:
  * Variable-to-set indexing and fast phi check in `Pass2AliasElimination`.
  * Canonical order fast score calculation in `Pass4MemoryCoalesceAssignment`.
  * Guarded expensive ASM/symbol string conversions in `Pass5FixLongBranches` when verbose logging is inactive.

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

* [Upstream Repository (GitLab)](https://gitlab.com/camelot/kickc) by Jesper Balman Gravgaard

* [Download](https://gitlab.com/camelot/kickc/-/releases) the newest Upstream Release 

* [Download](https://s3.sto3.safedc.net/kickc-nightly/kickc-release.zip) a nightly build 

* [Read](https://docs.google.com/document/d/1JE-Lt5apM-g4tZN3LS4TDbPKYgXuBz294enS9Oc4HXM/edit?usp=sharing) the Reference Manual

* [See](https://camelot.gitlab.io/kickc/files.html) the included C standard library

* [Follow](https://gitlab.com/camelot/kickc/issues) the features being developed

* [Discuss](https://www.facebook.com/groups/302286200587943/) the compiler and receive news on facebook

* [Chat](https://discord.gg/q6TFMZvfg5) on discord
