# Changelog

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

### Bug Fixes

- Fixed `jsr`/`dcp`/`bit` liveness, `volatile` indirect pointer guards, and stale `AsmProgramStaticRegisterValues` snapshots in `Pass5UnnecesaryLoadElimination`.
- Fixed edge cases in `Pass5FixLongBranches` (`isReturnTrampoline`), `Pass5HighSpeedJumpTableSwitch`, `Pass5DuplicateBlockElimination`, `Pass5AxRegisterCallingConventionOptimization`, and `Pass5SmallMemcpyUnrollingOptimization`.
