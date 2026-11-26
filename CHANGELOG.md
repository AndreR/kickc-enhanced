# Changelog

## [0.11.0] - 2026-11-26

### Code Size Optimizations & Static Analysis

- **Value Range Propagation & 16→8 Bit Narrowing (#56):** Extended `Pass2RangeNarrowing` with bounded fixpoint interval analysis (`[min, max]` across SSA statements & PHIs) to automatically narrow provably bounded 16-bit expressions (`+`, `-`, `&`, `|`, `^`, `>>`) to 8-bit.
- **Transitive Dead Data & Table Elimination (#59):** Added `PassNTransitiveDeadDataElimination` to perform whole-program reachability traversal from entry points and purge unreachable procedures and global data tables.
- **Cross-Scope Scalar Constant Inlining (#63):** Extended `Pass2ConstantInlining` and `Pass4CodeGeneration` to inline non-address-taken named scalar `const` values across scopes as immediate operands without emitting redundant `.const`/`.label` storage.
- **If-to-LUT Data-Flow Synthesizer (#64):** Added `Pass2IfToLutOptimization` to convert dense contiguous constant-assignment `if-else-if` chains into compact read-only lookup tables (with VRP bounds-check elision when proven in range).
- **Pass-5 Tail-Merging & Shared Epilogues (#61):** Added `Pass5TailMergingOptimization` (integrated with `Pass5RoundContext`) for intra-scope suffix tail-merging and segment-safe cross-procedure epilogue sharing.

### Language, Hardware & ABI Features

- **Selective Loop Unroll Pragmas (#58):** Added `#pragma unroll(N)`, `#pragma unroll(full)`, `#pragma unroll`, and `#pragma nounroll` with automatic elimination of dead zero-trip residual loops after exact-count peeling.
- **Automatic Struct Packing (#60):** Added opt-in `#pragma struct_pack(reorder)` / `#pragma struct_pack(default)` for deterministic 1B→2B→4B field grouping, and fixed `#pragma struct_model(unwind)` handling.
- **Foreign & Kernal Register Pinning (#74):** Added `__reg(A/X/Y)` parameter/return pinning, fixed-address external prototypes (`= $addr`), `__carry` return extraction, cycle-safe register swap scheduling, and conservative `CLOBBER_ALL` protection across foreign calls.
- **Bit-Addressable MMIO Variables (#75):** Added `__bit(addr, n)` declarations (`Pass1BitAddressLowering`) with zero RAM/ZP storage overhead, synthesizing `BIT`/`BMI`/`BPL` (bit 7), `BIT`/`BVS`/`BVC` (bit 6), and read-modify-write `ORA`/`AND`/`STA` instructions.
- **Register-Mapped Multi-Return Structs & Structured Binding (#76):** Added `auto [a, b] = expr;` structured binding syntax and `A/X` (2-byte) / `A/X/Y` (3-byte) register-mapped struct return allocation (`Pass4RegisterUpliftPotentialInitialize` + `Pass5MultiReturnStructOptimization`).
- **Compile-Time Function Execution (#77):** Added `constexpr` function support with an embedded SSA/CFG CTFE interpreter (`Pass2ConstexprEvaluator`) supporting arithmetic, loops, recursion, and `const` array lookups with zero 6502 code emitted for compile-time-only helpers.
- **Selective 6502 Bytecode VM (#62):** Added `__bytecode` / `#pragma bytecode(on|off)` / `-bc` (`Pass4BytecodeGenerator`) emitting compact 1-byte stack bytecode streams and an on-demand 6502 interpreter (`__bc_vm_exec`, zero overhead when unused).
- **Scoped Convenience Control (#67):** Added `#pragma convenience(push, on|off)` / `#pragma convenience(pop)` and `__attribute__((convenience))` / `__convenience` for per-procedure convenience math activation with zero overhead when disabled.
- **Constant-Specialized Arithmetic Synthesizer (#69):** Added `Pass2ConstantArithmeticSynthesizer` for optimal NAF/shift-add constant multiplication (8/16-bit) and bit-exact 0..255-verified reciprocal division and modulo (`x / C`, `x % C`) without runtime helper calls.

### Tooling & Diagnostics

- **Source-Level Code Size & Static Cycle Profiler (#57):** Added `-csz` / `-codesize` (`CodeSizeProfiler`) generating human-readable `.csz` and structured `.csz.json` reports with segment summaries, Top-20 procedures and C source lines by bytes and static 6502 cycles, and line-by-line byte/cycle accounting.

### Bug Fixes

- Fixed transitive `X`/`A` liveness validation in `Pass5AxRegisterCallingConventionOptimization`.

## [0.10.2] - 2026-11-25

### Optimizer & Code Generation

- **Pass 0 (AST / Control Flow):**
  - Emit direct short-circuit jump code for conditional expressions.
  - Gate value-context `&&`/`||` rewriting on RHS side effects and fix `PrePostModifierHandler` sequence-point handling on short-circuited branches.
- **Pass 1 (Inlining):**
  - Added controlled auto-inlining for small, non-recursive leaf procedures (`Pass1ProcedureInline`).
- **Pass 2 (SSA):**
  - Added 8-bit range narrowing for bounded 16-bit signed differences (`Pass2RangeNarrowing`).
  - Added bit-shift canonicalization for constant byte-aligned right shifts (`Pass2BitShiftCanonicalization`).
  - Fixed LICM procedure-CFG preheader construction and eliminated O(B_total) predecessor scans in CSE/LICM.
  - Preserved memory/`volatile` condition operands in `Pass2ConditionalJumpSimplification`.
- **Pass 4 (Register Allocation):**
  - Conflict-free `-Ouplift` register budget handling and phi-interference/interrupt zero-page coalescing fixes.
- **Pass 5 (ASM & Peephole):**
  - Added bit-accurate `{N, Z, C, V}` backward liveness and scoped label/multi-hop resolution (`findLabelIndex`).
  - Added automatic `A`-register return ABI (`Pass5ARegisterReturnOptimization`) with coalesced zero-page return and recursion/tail-call guards.
  - Extended `Pass5TailCallOptimization` to allow `jsr; rts -> jmp` for non-`void` callers calling safe `void` callees.
  - Added intra-block overwritten-store DSE and byte-granular DSE (`Pass5DeadStoreElimination`).
  - Added static carry inference to eliminate redundant `clc` instructions when `C=0` is guaranteed (`Pass5StandardPeepholeOptimization`).
  - Guarded `Pass5CarryFlagReturnOptimization` against `.byte $2c`/`.byte $24` byte-skippers, ordered it before `Pass5MultiEntryByteSkipping`, and extended carry-return detection to tail-call chains (`jmp`) between carry-return procedures.
  - Fixed `isRegDeadAlongPath` at `rts` for non-`void` procedures whose return value does not use `A`/`X`/`Y`, unlocking `Pass5LoopRegisterPinning` in non-`void` leaf loops.
  - Added CFG-meet pointer tracking, `INY`/`DEY` strength reduction, half-pointer reload elimination, and `knownZp` fallthrough.
  - Added NMOS 6510 illegal opcode rules (`ILL-04`..`ILL-08`) with pointer-aliasing, `FLAG_N`, and `$ff` liveness guards.
  - Improved Pass 5 throughput via single-pass tombstones (`Pass5RedundantStoreAndReloadElimination`), scope-local scans, symbolic `.const` resolution in `Pass5MultiEntryByteSkipping`, and cached O(1) `AsmVarLookupIndex` lookups.

### Fragments

- Added `_neg__byte_` / `_neg__sbyte_` base fragments, little-endian low-byte synthesis rules, tribyte `mapSToU`, and main-memory pointer synthesis (`p..mN`).
- Added `vwsm1_ge_vwum2_then_la1` and `vwsm1_le_vwum2_then_la1` for mixed signed/unsigned 16-bit comparisons.
- Restored `d` (dword) type support in `AsmFragmentSignature.g4`.

### Frontend, Type System & Thread Safety

- Fixed multi-designator and bitfield initializers (including anonymous structs inside unions), shift width bounds, and C23/preprocessor edge cases.
- Fixed constant folding before child narrowing, signed-cast edge cases, `DWORD` limits, and `-fconvenience` division/modulo handling.
- Resolved multi-core data races, isolated static compiler state, enabled lock-free fragment cache access, and precompiled regex patterns.

### Compiler Performance & Register Allocation

- **Core IR & Passes 1–3:** Added O(1) `ControlFlowGraph.BlockIndex`, `Program.procedureCompilationMap`, `Variable.cachedRef`, `VariableReferenceInfos` caching, selective `ProgramScope` cache removal, lazy `VarAssignments.Index`, `BitSet` backward liveness (`PassNCalcLiveRangeVariables`), and guarded verbose logging/redundant loop-analysis gating.
- **Pass 4 & Fragments:** Added `Pass4DirectTrialEvaluator` with `FastClobberIndex` (zero-`AsmProgram`/zero-ANTLR trial scoring and thread-local register patching), fast ZP+ZP coalescing, buffered fragment cache I/O, and opt-in `-Olocalsearch` Phase-3 1-opt/2-opt local-search refinement (`Pass4RegisterUpliftLocalSearch`).
- **Pass 5:** Added `Pass5RoundContext` dirty-scope gating and shared per-round line/alias caches across Pass 5 optimizations, widespread O(1) tombstone deletion, `BlockSignature` bucketing in `Pass5DuplicateBlockElimination`, and analytical branch safety with `AsmLoopAlign` interval padding in `Pass5FixLongBranches`.

### Bug Fixes

- Fixed `jsr`/`dcp`/`bit` liveness, `volatile` indirect pointer guards, and stale `AsmProgramStaticRegisterValues` snapshots in `Pass5UnnecesaryLoadElimination`.
- Fixed edge cases in `Pass5FixLongBranches` (`isReturnTrampoline`), `Pass5HighSpeedJumpTableSwitch`, `Pass5DuplicateBlockElimination`, `Pass5AxRegisterCallingConventionOptimization`, and `Pass5SmallMemcpyUnrollingOptimization`.
