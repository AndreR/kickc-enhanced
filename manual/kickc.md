

# KickC Enhanced Reference Manual

KickC is an optimizing C-to-6502 cross-compiler that generates readable, compact assembler code for MOS 6502-family systems (such as the Commodore 64, VIC-20, Plus/4, Atari 8-bit, NES, MEGA65, and Commander X16).

> [!NOTE]
> KickC is in beta. The compiler may occasionally fail with an error message that is difficult to interpret, or generate assembler code that does not work correctly. Breaking changes (to syntax, semantics, etc.) may be introduced in future versions. Problems can be reported in the project repository.

## Table of Contents

- [1 What is KickC?](#1-what-is-kickc)
  - [1.1 The KickC Language](#11-the-kickc-language)
  - [1.2 Supported 6502-based platforms](#12-supported-6502-based-platforms)
  - [1.3 Optimized and Readable Assembler Code](#13-optimized-and-readable-assembler-code)
  - [1.4 Getting Started](#14-getting-started)
- [2 KickC Language Reference](#2-kickc-language-reference)
  - [2.1 Variables](#21-variables)
  - [2.2 Data Types](#22-data-types)
  - [2.3 Expressions and Operators](#23-expressions-and-operators)
  - [2.4 Statements](#24-statements)
  - [2.5 Functions](#25-functions)
  - [2.6 Comments](#26-comments)
  - [2.7 Splitting Code into Multiple Files](#27-splitting-code-into-multiple-files)
  - [2.8 Variable Directives](#28-variable-directives)
  - [2.9 Function Directives](#29-function-directives)
  - [2.10 Controlling the Compilation](#210-controlling-the-compilation)
  - [2.11 Standard Libraries](#211-standard-libraries)
  - [2.12 Comparison with Standard C](#212-comparison-with-standard-c)
- [3 Working with KickC](#3-working-with-kickc)
  - [3.1 Controlling Output Files](#31-controlling-output-files)
  - [3.2 KickC Command Line Reference](#32-kickc-command-line-reference)
- [4 The Compiler Architecture](#4-the-compiler-architecture)
  - [4.1 Loading and Parsing (Pass 0)](#41-loading-and-parsing-pass-0)
  - [4.2 SSA Control Flow Graph & Symbol Table Generation (Pass 1)](#42-ssa-control-flow-graph-symbol-table-generation-pass-1)
  - [4.3 SSA Optimization Micro-Passes (Pass 2)](#43-ssa-optimization-micro-passes-pass-2)
  - [4.4 Live-Range Analysis & Call Graph Resolution (Pass 3)](#44-live-range-analysis-call-graph-resolution-pass-3)
  - [4.5 Register Allocation & Variable Uplift (Pass 4)](#45-register-allocation-variable-uplift-pass-4)
  - [4.6 Assembler Code Generation (Pass 4)](#46-assembler-code-generation-pass-4)
  - [4.7 Pass 5 Assembler Code Optimization](#47-pass-5-assembler-code-optimization)
- [5 Assembler Fragment Subsystem](#5-assembler-fragment-subsystem)
  - [5.1 Fragment Naming Conventions](#51-fragment-naming-conventions)
  - [5.2 Fragment Synthesis & Adding Missing Fragments](#52-fragment-synthesis-adding-missing-fragments)

---

# 1 What is KickC?

KickC is a C-compiler for 6502-based platforms creating optimized and readable assembler code.

The KickC language is classic C with a few intentional differences that match the 8-bit 6502 architecture, plus selected features from **C11**, **C23**, and **C2Y** and low-level 6502 extensions.

## 1.1 The KickC Language

The language is largely standard C (based on C99 with C11/C23/C2Y features) and supports most core language constructs, so programmers familiar with C can get started quickly.

Here is a simple "hello world" program that prints "hello world!" on the screen:

```c
#include <stdio.h>
void main() {
    printf("hello world!");
}
```

Unlike desktop C compilers, KickC does not use a dynamic runtime stack frame or automatic integer promotion to 16-bit `int`, since both are costly on an 8-bit CPU. Instead, KickC compiles code via an SSA (Static Single Assignment) Control Flow Graph, a static call graph, global variable live-range analysis, and Zero-Page register allocation.

The language also provides extensions for 8-bit hardware, such as split low/high word arrays (`__lohi`), struct-of-arrays flattening (`__soa`), packed 4-bit nibble arrays (`uint4_t`), fixed-point math types and suffixes (`fixed8_8`, `fixed16_8`, `f8`, `f16`), the 16-bit register calling convention (`__ax`), auto carry-flag boolean returns (`__carry`), cycle-exact delay synthesis (`delay_cycles`), binary asset embedding (`#embed`), and memory segment typing (`#pragma segment_type`).

## 1.2 Supported 6502-based platforms

The KickC compiler includes target configurations and linker scripts for the following platforms out-of-the-box:
  - Commodore VIC-20
  - Commodore 64
  - Commodore Plus/4 (also usable for Commodore 16 and Commodore 116)
  - Atari 2600
  - Atari XL/XE
  - Nintendo NES
  - MEGA65
  - Commander X16

For convenience, KickC supports compiling, assembling, and executing directly in an emulator from the command line (`-e`).

Custom platforms and memory configurations can be added by supplying a platform target definition (`.tgt`) and a KickAssembler linker script (`.ld`).

KickC generates assembler code for the [Kick Assembler](http://theweb.dk/KickAssembler). Specifically, the compiler supports the `6502`, `6502x` (with stable undocumented/illegal opcodes), `65C02`, `65CE02`, and `45GS02` CPUs.

## 1.3 Optimized and Readable Assembler Code

The compiler applies the following optimization techniques to produce readable 6502 assembler:
  - **Constant propagation and folding:** Immediate evaluation of constant expressions at compile time.
  - **Zero-Page and CPU register allocation:** Phi-lifting, variable live-range coalescing, and combination uplift into registers A, X, and Y.
  - **Static register and flag tracking:** Interprocedural and intra-block tracking of accumulator, index registers, and processor status flags (Carry, Zero, Negative).
  - **NMOS-6510 Undocumented Opcodes (`mos6502x` / `rom6502x`):** Automatic synthesis of stable, deterministic green opcodes (`LAX`, `SAX`, `ALR`, `DCP`, `ISC`).
  - **Tail Call Optimization (TCO):** Automatically converts tail `JSR <proc>; RTS` sequences into direct `JMP <proc>` instructions, and relaxes long-branch return trampolines.
  - **Switch Jump Tables:** Dense `switch` statements are compiled to a split Lo/Hi RTS dispatch table instead of a linear comparison chain (see Section 2.4.3 for the conditions).
  - **Pointer-to-SMC Optimization:** Replaces sequential Zero-Page pointer dereferences in writable RAM segments with direct absolute self-modifying code operands.
  - **Dead Store & Dead Load-Store Elimination:** Uses recursive `CanonicalResolver` symbol tracking to eliminate dead SSA temporaries and unused register loads.
  - **Page Boundary Loop Alignment:** Aligns small inner loops to avoid the 1-cycle penalty for branches that cross a 256-byte page boundary.
  - **Removal of unused functions and data:** Whole-program analysis removes unreachable procedures and unreferenced global tables.

Below is a slightly more complex version of hello world, which prints "hello world!" with an added space between each letter on the first and third line of the C64 default screen at 0x400. This example illustrates how the KickC compiler creates optimized readable 6502 assembler.

**helloworld2.c**
```C
char* screen = 0x400;

void main() {
    char* hello = "hello world!";
    print2(screen, hello);
    print2(screen+2*40, hello);
}

void print2(char* at, char* msg) {
    char j=0;
    for(char i=0; msg[i]; i++) {
        at[j] = msg[i];
        j += 2;
    }
}

```

**helloworld2.asm**

```asm
.label screen = $400
main: {
    lda #<screen
    sta print2.at
    lda #>screen
    sta print2.at+1
    jsr print2
    lda #<screen+2*$28
    sta print2.at
    lda #>screen+2*$28
    sta print2.at+1
    jsr print2
    rts
    hello: .text "hello world!@"
}
print2: {
    .label at = 2
    ldy #0
    ldx #0
  b1:
    lda main.hello,x
    sta (at),y
    iny
    iny
    inx
    lda main.hello,x
    cmp #0
    bne b1
    rts
}

```

The KickC compiler uses the following insights to optimize the helloworld2 program:
  - The screen pointer is never modified and is therefore a constant location in memory.
  - The second parameter to the `print2`-function (msg) always has the same value main.hello, so that can be hardcoded inside the method body instead of parsing it. 
  - The contents of `msg[i]` (ie. the hardcoded main.hello string) can be addressed using simple indexing.
  - The at-parameter in print2 is truly variable, so it is placed on zero-page ($2 and $3) and indirect indexing can be used for addressing the contents of `at[j]`.
  - The X-register is optimal for the `i` variable in the print2 function as it is good at indexing and incrementing.
  - The Y-register is optimal for the `j` variable in the print2 function as it is good at indirect indexing and incrementing.
  - When 2 is added to the `j`-variable it is better to do INY twice than to use addition.
  - The A-register is optimal for holding the current character c of the message being moved to the screen as LDA and STA can be efficiently indexed by X and indirect indexed by Y.

When generating Kick Assembler code the KickC compiler tries to ensure that the assembler code is readable and corresponds to the source C program as much as possible. This includes using the same names, using scopes and recreating constant calculations in assembler, when possible.

The KickC Compiler creates readable 6502 assembler code for the helloworld2 program by:
  - Using the variable and function-names `screen`, `hello`, `main`, `print2`, `at`  in the generated code.
  - Recreating the calculation of the constant `screen+2*40` in the assembler code as `screen+2*$28`.
  - Creating a local named scope in the assembler-code for the methods `main` and `print2` by enclosing them in curly braces.
  - Placing method-local data and labels inside the method scope. This allows other assembler code to access the local data/labels using dot-syntax eg. `main.hello` or `print2.at`.

## 1.4 Getting Started

The KickC development is hosted on gitlab (https://gitlab.com/camelot/kickc). Here it is possible to follow the development and to download the latest binary release.

You install KickC on your own computer by:  
  - Download the newest KickC release from  (https://gitlab.com/camelot/kickc/-/releases).  
  - Unpack the zip-file to a folder of your own choice. 

The zip-file contains the following: 
  - A bin Folder containing `bat/sh-files` for running KickC.
  - An examples Folder containing some example KickC programs.
  - An include Folder containing header-files for useful library functions usable in your own program.
  - A lib Folder containing C-files implementing the useful library functions usable in your own program.
  - A jar Folder containing the KickC JAR-file plus a few other JARs needed for running KickC (antlr4-runtime, picocli and KickAssembler).

  - A target Folder containing target platform specification and linker files for each supported platform. 
  - A fragment Folder containing small asm-files used by the compiler to create the assembler code.
  - This manual in PDF-format and some files with license-information.

KickC is written in Java. To use  KickC you need to download and install a Java runtime from https://www.java.com or https://jdk.java.net/. Java must be added to your PATH, or the environment variable JAVA_HOME must point to the folder containing the Java installation. 

> [!NOTE]
> KickC runs a lot faster on 64bit Java than on 32bit Java. You are therefore encouraged to ensure that your Java is a 64bit version. You can check by executing `java -version` in a Terminal/Command Prompt.

After installing KickC and you can compile a simple sample KickC program by doing the following: 

**MacOS**
  - Start a Terminal
  - cd to the folder containing KickC 
  - Enter the command `bin/kickc.sh -e examples/helloworld/helloworld.c`

**Windows**
  - Start a Command Prompt
  - cd to the folder containing KickC 
  - Enter the command `bin\kickc.bat -e examples\helloworld\helloworld.c`

This compiles the helloworld KickC program `examples/helloworld/helloworld.c`   
and produces assembler code in `examples/helloworld/helloworld.asm`.  

The command line option `-e` instructs the compiler to both assemble the ASM code and execute the resulting binary program in a suitable emulator.

If you prefer not to assemble and run the code then just leave the `-e `option out. The compiler will then just produce an ASM-file that can then be assembled using KickAssembler, producing a runnable program that can be executed using an emulator or transfered to the real 8-bit hardware.

The emulator must of course be installed on your machine and available in the PATH for (-e) to work properly. KickC supports compiling for a number of different platforms, and uses different emulators for each platform. The following are the emulators used by the built-in target platforms:
  - VICE for Commodore 64, VIC 20 and Plus/4. http://vice-emu.sourceforge.net/
  - Atari800 for Atari 8-bit (5200, 400/800/XL/XE). https://atari800.github.io/
  - Nestopia for Nintendo NES. http://nestopia.sourceforge.net/
  - Stella for Atari 2600. https://stella-emu.github.io/
  - Xemu for MEGA65. https://github.com/lgblgblgb/xemu
  - Commander X16 Emulator for Commander X16. https://www.commanderx16.com/

Further example programs are located in the `examples` folder of the release. An example is compiled and executed the same way as `helloworld.c` above, by passing its source file to `kickc.sh` (macOS/Linux) or `kickc.bat` (Windows) with the `-e` option. For instance, `simple-multiplexer.c` is a C64 program (a sprite multiplexer moving 32 balloons in a sine on the screen) that is run in the VICE emulator.

To create your own KickC-programs use any text-editor to create a source file containing a `void main()` function, save it to the file system (using the `.c` extension is recommended) and compile it by passing it to `kickc.sh` (on MacOs) or `kickc.bat` (on Windows).

# 2 KickC Language Reference

KickC is a C-family language based on C99 with selected features from C11, C23, and C2Y, adapted to 6502 code generation.

## 2.1 Variables

Variables are declared by **type name** and can include an optional **initialization assignment**:

- `char size = 12;` declares an unsigned char variable named `size` initialized to 12.
- Uninitialized global variables are initialized with the **default value zero**.
- Multiple variables of the same type can be declared together: `unsigned int a = 4, b;`.
- Variables can be initialized with the standard C23 empty initializer `{}`: `struct Point pt = {};` or `char buffer[32] = {};`, ensuring all members are set to zero.
- Variables can be declared at any point in the program (global scope, local block scope, or inside `for`/`if`/`switch` statement headers).

## 2.2 Data Types

### 2.2.1 Integers & Digit Separators

KickC supports standard ISO C integer types as well as fixed-size 6502-friendly aliases and the `<stdint.h>` exact-width types:

| Type Name | Standard Header Type | Size (Bytes) | Representation / Range |
| :--- | :--- | :---: | :--- |
| `char`, `unsigned char`, `byte`, `unsigned byte` | `uint8_t` | 1 | Unsigned 8-bit integer: [0; 255] |
| `signed char`, `signed byte` | `int8_t` | 1 | Signed 8-bit integer (two's complement): [-128; 127] |
| `unsigned short`, `unsigned int`, `unsigned`, `word`, `unsigned word` | `uint16_t` | 2 | Unsigned 16-bit integer: [0; 65,535] |
| `short`, `signed short`, `int`, `signed int`, `signed`, `signed word` | `int16_t` | 2 | Signed 16-bit integer (two's complement): [−32,768; +32,767] |
| `uint24_t` | `uint24_t` | 3 | Struct `{ uint16_t lo; uint8_t hi; }` defined in `<stdint.h>`; value range [0; 16,777,215] |
| `int24_t` | `int24_t` | 3 | Struct `{ uint16_t lo; int8_t hi; }` defined in `<stdint.h>`; value range [-8,388,608; +8,388,607] |
| `unsigned long`, `dword`, `unsigned dword` | `uint32_t` | 4 | Unsigned 32-bit integer: [0; 4,294,967,295] |
| `long`, `signed long`, `signed dword` | `int32_t` | 4 | Signed 32-bit integer (two's complement): [−2,147,483,648; +2,147,483,647] |

Key behavioral rules:
- Unadorned 8-bit integer types (`char`, `byte`) default to **`unsigned`**.
- Unadorned 16-bit and 32-bit types (`short`, `int`, `long`) default to **`signed`**.
- The 6502-friendly types `word` and `dword` default to **`unsigned`**.
- `uint24_t` and `int24_t` are structs of exactly 3 bytes. The bytes are accessed via the members `lo` and `hi`; they are not built-in arithmetic types.

#### Integer Literals & Digit Separators (C23)

Integer literals can be expressed in decimal, hexadecimal, or binary:

| Format | Prefix | Examples |
| :--- | :--- | :--- |
| Decimal | None | `12`, `53280`, `1_000_000` |
| Hexadecimal | `0x` or `$` | `0x40`, `$dc01`, `0xff_00` |
| Binary | `0b` or `%` | `0b101`, `%1100_1100`, `0b1010_0101` |

Digit separators (`_` or `'` as per C23) may be used freely within decimal, hexadecimal, and binary literals to improve readability when defining hardware bitmasks, screen coordinates, or memory addresses.

#### Character Literals & Escape Sequences

Character literals are unsigned 8-bit chars enclosed in single quotes (`'c'`). The supported escape sequences are:
- `'\n'`: newline
- `'\r'`: carriage return
- `'\f'`: form feed
- `'\t'`: horizontal tab
- `'\'`: single quote
- `'\"'`: double quote
- `'\\'`: backslash
- `'\0'`: null character

Character encoding is determined by the active `#pragma encoding(...)` or literal suffix (see Section 2.10.5).

### 2.2.2 Packed 4-Bit Nibbles (`uint4_t` / `__nibble`)

KickC supports 4-bit unsigned integers via the built-in type `uint4_t` (aliases: `nibble`, `__nibble`; no header required):

```c
uint4_t color_cache[1000]; // Allocates (1000 + 1) / 2 = 500 bytes in memory
```

Features:
- **Automatic pairwise byte packing:** Array elements are stored pairwise in memory (2 nibbles per byte), halving RAM usage for 4-bit data structures (such as C64 Color-RAM caches).
- **Transparent indexing:** Reads and writes (`color_cache[i] = 7;` or `val = color_cache[i];`) are compiled to the required nibble extraction, shifting, and masking.
- **Size queries:** `countof(color_cache)` evaluates to 1000 (element count), while `sizeof(color_cache)` evaluates to 500 (allocated byte size).

### 2.2.3 Fixed-Point Numbers & Literal Suffixes

Fixed-point arithmetic is a common approach for physics, subpixel motion, and trigonometry on 8-bit systems without floating point support. KickC provides fixed-point types in `<fixedpoint.h>` along with native compiler literal suffixes:

| Type | Int / Frac Bits | Range | Typical Use Case |
| :--- | :---: | :--- | :--- |
| `fixed8_8` / `ufixed8_8` | 8.8 (16-bit) | Signed: [-128.0; +127.996]<br>Unsigned: [0.0; 255.996] | Single-screen sprites (< 256 px), velocities, physics |
| `fixed16_8` / `ufixed16_8` | 16.8 (24-bit) | Signed: [-32768.0; +32767.996]<br>Unsigned: [0.0; 65535.996] | Scrolling world coordinates, camera tracking |
| `fixed8_16` / `ufixed8_16` | 8.16 (24-bit) | Signed: [-128.0; +127.99998]<br>Unsigned: [0.0; 255.99998] | High-precision angles, normalized vector directions |
| `fixed16_16` / `ufixed16_16` | 16.16 (32-bit) | Signed: [-32768.0; +32767.99998]<br>Unsigned: [0.0; 65535.99998] | 3D transformation matrices, raycasting, Z-buffers |
| `fixed24_8` / `ufixed24_8` | 24.8 (32-bit) | Signed: [-8388608.0; +8388607.996]<br>Unsigned: [0.0; 16777215.996] | Large coordinates with subpixel precision |
| `fixed8_24` / `ufixed8_24` | 8.24 (32-bit) | Signed: [-128.0; +127.9999999]<br>Unsigned: [0.0; 255.9999999] | High-precision normalized wave calculations |

#### Native Fixed-Point Literals

Literals can be suffixed directly in source code:
- `f8`: 8.8 fixed-point (e.g. `fixed8_8 pos = 10.5f8;`)
- `f16`: 16.16 fixed-point (e.g. `fixed16_16 m = 1.5f16;`)
- `f24`: 8.24 fixed-point (e.g. `fixed8_24 ratio = 0.7071f24;`)

There are no literal suffixes for the 16.8, 8.16, and 24.8 types.

#### Transparent Member Access & Fixed-Point Math

`fixed8_8` and `ufixed8_8` are plain 16-bit integer typedefs; use the macros `fixed_int(f)` and `fixed_frac(f)` to access the integer and fractional part. The wider types (16.8, 8.16, 16.16, 24.8, 8.24) are unions whose members `.integer` and `.frac` give direct access to the integer part and the fraction:

```c
#include <fixedpoint.h>

fixed16_16 player_x = 100.5f16; // Integer 100, fraction 0x8000
player_x.integer += 5;          // Modify integer part directly without shifting
uint16_t sub = player_x.frac;   // Read fraction

// Fixed-point math operations:
fixed8_8 pos = 12.5f8;
fixed8_8 vel = -0.25f8;
pos = fixed_add(pos, vel);
fixed8_8 prod = fixed_mul(pos, 2.0f8);
int8_t px = fixed_int(pos);   // Integer extraction
```

### 2.2.4 Booleans

KickC provides a standard boolean type `bool` (with header `<stdbool.h>`). The boolean literals are `true` (1) and `false` (0). Functions can also return conditions directly in the CPU Carry flag via `__carry` (see Section 2.5.4).

```c
#include <stdbool.h>
bool active = true;
```

### 2.2.5 Pointers and nullptr (C23)

Pointers are declared with `type*`. KickC supports pointers to all integer types, fixed-point types, structs, unions, and booleans:

```c
char* screen = (char*)0x0400;
uint16_t* ptr_to_word;
unsigned char** screenptr = &screen; // Pointer to pointer
```

#### Function Pointers

KickC supports function pointers using standard C syntax:

```c
void my_routine() { ... }
void (*call_ptr)() = &my_routine;

void dispatch() {
    call_ptr(); // Indirect procedure call via JMP ($xxxx)
}
```

#### The `nullptr` Keyword (C23)

KickC supports the C23 `nullptr` keyword, representing a type-safe null pointer constant:

```c
char* ptr = nullptr;
if (ptr != nullptr) { ... }
```

#### RAM Pointer-to-SMC Optimization

When sequential pointer dereferences (`*ptr++ = val`) occur in writable RAM segments, KickC's Pass 5 optimizer automatically transforms indirect-indexed Zero-Page addressing `sta (ptr),y` into direct absolute self-modifying code `sta $ffff` with dynamic operand increments. This reduces Zero-Page register pressure and saves 2 CPU cycles per store.

### 2.2.6 Arrays & Advanced Array Layouts

Arrays are declared with `type a[]` or `type a[size]`. For most practical purposes, array variables are treated like pointers to their first element.

Arrays can be initialized with comma-separated literals, empty initializers (`{}`), zero-filling by specifying size, string literals, or compiled KickAssembler macros:

```c
signed char sums[3];                   // Zero-initialized 3-byte array
int fibs[] = { 1, 1, 2, 3, 5, 8 };     // 6-word initialized array
char msg[] = "hello";                  // 6 bytes (null-terminated with '\0')
char msg2[] = "hello"z;                // 5 bytes (non-zero terminated via suffix 'z')
bool flags[8];                         // Boolean array
```

> [!NOTE]
> Multidimensional arrays (`type arr[M][N]`) are not supported and are rejected with a `CompileError`. With `-Warraytype` the declaration is accepted with a warning but is treated as an array of pointers without row storage, so it must not be used. Use flat 1D arrays with index calculations, split arrays (`__lohi`), or arrays of structs (`__soa`) instead.

#### Split Low/High Word Arrays (`__lohi`)

Standard 16-bit word arrays on the 6502 require index doubling (`ASL`) and 16-bit pointer math. The `__lohi` directive declares a 16-bit array up to 256 elements split into parallel low- and high-byte tables in memory:

```c
__lohi uint16_t lookup[200];
__lohi uint16_t init_tab[4] = { 0x1234, 0x5678, 0x9abc, 0xdef0 };
```

Accessing `lookup[i]` lowers directly to two 4-cycle absolute indexed operations:
```asm
lda lookup, x      // Low byte
ldy lookup+200, x  // High byte
```
This requires **8 CPU cycles** (compared to 22+ cycles for normal word indexing) and does not clobber additional registers. `countof(lookup)` (200) and `sizeof(lookup)` (400) keep their usual values.

#### Direct Binary Asset Embedding (`#embed` - C23)

The C23 `#embed` directive allows embedding raw binary assets directly into array initializers at compile time:

```c
const uint8_t font[] = {
    #embed "assets/font.bin"
};

const uint8_t sprite_header[] = {
    #embed "assets/sprites.bin" limit(64) prefix(0x00) suffix(0xff)
};
```

Parameters supported:
- `limit(N)`: Embed at most $N$ bytes.
- `prefix(...)`: Prepend constant tokens before the embedded bytes. The separating comma is added by the compiler; a trailing comma inside the parameter (`prefix(0x00, )`) causes a parse error.
- `suffix(...)`: Append constant tokens after the embedded bytes (same rule: write `suffix(0xff)`, not `suffix(, 0xff)`).
- `if_empty(...)`: Tokens emitted if the file is 0 bytes.
- `__has_embed("file.bin")`: Preprocessor conditional operator to query asset availability.

### 2.2.7 Structures, Unions, and Bitfields

KickC supports standard C structures, C11 anonymous structures and unions, and ISO-C bitfields.

#### Struct-of-Arrays Flattening (`__soa`)

Arrays of structures normally require dynamic multiplication by `sizeof(struct)` at runtime. The `__soa` directive automatically transposes an array of structures up to 256 elements into parallel column arrays in memory:

```c
struct Particle {
    uint8_t x, y;
    int8_t  vx, vy;
};

__soa struct Particle particles[32];
```

Accessing `particles[i].x` compiles to a single absolute indexed instruction (4 cycles); each member is stored in its own table of 32 bytes (e.g. `.vy` at `particles+96`):
```asm
lda particles, x        // Reads .x
lda particles+96, x     // Reads .vy
```
This avoids Zero-Page pointer arithmetic and runtime multiplication. `sizeof(particles)` (128 bytes) and `countof(particles)` (32) keep their usual values.

#### Anonymous Structures & Unions (C11 §6.7.2.1)

Structures and unions can be nested anonymously, flattening their members directly into the enclosing scope:

```c
struct Vector {
    union {
        struct { uint8_t x, y; };
        uint16_t raw;
    };
    uint8_t id;
};

struct Vector vec;
vec.x = 10;     // Direct access to anonymous union member
vec.y = 20;
vec.raw = 0x1234;
```

KickC's Pass 5 unrolls small 2..4 byte structure and union copy assignments directly into inline `LDA/STA` pairs, eliminating loop overhead.

#### ISO-C Bitfields in Structs

KickC supports ISO-C bitfields:

```c
struct StatusRegister {
    uint8_t carry     : 1;
    uint8_t zero      : 1;
    uint8_t mode      : 3;
    uint8_t reserved  : 3;
};
```

The compiler packs adjacent bitfields into container integer units and generates bit masking (`AND`, `ORA`, and shifts) for reads and writes.

### 2.2.8 Enumerations & Typed 8-Bit Enums (C23)

Standard C enums promote to 16-bit integers. KickC supports standard enums and C23 typed enumerations, which use 1-byte storage:

```c
#include <stdint.h>

enum GameState : uint8_t {
    STATE_TITLE   = 0,
    STATE_INGAME  = 1,
    STATE_GAMEOVER = 2
};

enum GameState current_state = STATE_TITLE; // Stored in 1 byte, no 16-bit promotion
```

### 2.2.9 BCD (Binary Coded Decimal) Arithmetic

6502 processors include a hardware decimal mode (`SED`/`CLD`). KickC provides `<bcd.h>` with dedicated types `bcd8_t` and `bcd16_t`:

```c
#include <bcd.h>

bcd8_t score = 0x45;
score = bcd8_add(score, 0x15); // Result: 0x60 (45 + 15 = 60 in BCD)
bcd16_t high_score = bcd16_add(0x1999, 0x0001); // Result: 0x2000
```

The runtime functions manage the processor decimal flag, and `__interrupt(hardware_all)` handlers execute `CLD` on entry to avoid decimal-mode corruption.

### 2.2.10 Constants

Constants are declared with `const`. The compiler evaluates constant expressions at compile time and propagates constants across scopes:

```c
const uint8_t SPRITE_COUNT = 8;
const char* const SCREEN = (char*)0x0400; // Constant pointer to constant data
```

### 2.2.11 No Floating Point Types

KickC does not support IEEE floating point types (`float`, `double`): the 6502 has no hardware FPU, and software floating point emulation requires a large amount of memory and CPU cycles. Use the fixed-point types from `<fixedpoint.h>` (`fixed8_8`, `fixed16_8`, `fixed16_16`), which are implemented with native 6502 integer instructions.

## 2.3 Expressions and Operators

Expressions in KickC consist of operands and operators. Operands are either literals, variables, or constant symbols. All standard expression operators from C exist in KickC, extended by 6502-specific low/high operators, compile-time operators (`countof`, `_Generic`), and cycle-exact intrinsics.

### 2.3.1 Arithmetic Operators

The arithmetic operators support numeric calculations:

- `a + b`: Addition
- `a - b`: Subtraction
- `- a`: Unary Negation
- `+ a`: Unary Plus
- `a * b`: Multiplication
- `a / b`: Division
- `a % b`: Modulo / Remainder

> [!NOTE]
> Write the modulo operator with surrounding spaces. `a%10` is lexed as `a` followed by the binary literal `%10` and causes a parse error.

#### 6502 Arithmetic Optimizations

The MOS 6502 has no multiplication or division instructions, so runtime support for these operators is limited. Calculations on constants are always allowed. KickC applies the following optimizations and provides library routines:
- **Compile-time Constant Evaluation:** All calculations on constants (e.g. `40 * 25`, `1024 / 64`) are evaluated during compilation at no runtime cost.
- **Factored Shift-Add Multiplication:** Multiplications with constant factors (e.g. `x * 24`, `x * 40`, `x * 320`) are rewritten into sequences of shifts and additions without 16-bit zero-extension.
- **Reciprocal Constant Division & Modulo:** Constant divisions and modulo operations on 8-bit integers (such as `/ 10`, `% 10`) are optimized via reciprocal multiplication and bit shifts in Pass 2 SSA.
- **Quarter-Square Multiplication (`<multiply.h>`):** Table-based multiplication (`fast_mul8u`, `fast_mul16u`, `fast_mul16s`) with constant execution time, using squares tables.
- **Carry-Chain 16-Bit Division (`<math.h>` / `<division.h>`):** Unrolled 6502 carry-chain routines (`div16u`, `rem16u`, `div8u`, `rem8u`).

General multiplication, division, and modulo of two variables are not generated inline and require a library function call. Multiplication of a variable by an unsigned constant is converted to shifts and additions.

### 2.3.2 Bitwise Operators

Bitwise operators manipulate the individual bits of numeric values:

- `a & b`: Bitwise AND
- `a | b`: Bitwise OR
- `a ^ b`: Bitwise XOR
- `~ a`: Bitwise NOT (one's complement)
- `a << n`: Bitwise Shift Left by $n$ bits
- `a >> n`: Bitwise Shift Right by $n$ bits

Pass 5 peephole optimizations automatically recognize 16-to-8 bit right-shifts by 2 or more bits and collapse them directly into accumulator `LSR` sequences, eliminating redundant high-byte stores.

### 2.3.3 Relational Operators

Relational operators compare values and produce a boolean result (`true` or `false`):

- `a == b`: Equal to
- `a != b`: Not equal to
- `a < b`: Less than
- `a <= b`: Less than or equal to
- `a > b`: Greater than
- `a >= b`: Greater than or equal to

KickC provides fragments for unsigned and signed 8-bit, 16-bit, and 32-bit comparisons. Comparisons against zero (e.g. `if (val != 0)`) use the 6502 status flags set by a preceding `LDA`, `TAX`, or math operation, so no `CMP #0` is emitted.

### 2.3.4 Logical Operators

Logical operators operate on boolean expressions:

- `a && b`: Logical AND (short-circuit evaluated)
- `a || b`: Logical OR (short-circuit evaluated)
- `! a`: Logical NOT

When `&&` and `||` are used in control flow (`if`, `while`, `do-while`), evaluation short-circuits: in `if (a || b)`, `b` is never evaluated if `a` is true; in `if (a && b)`, `b` is never evaluated if `a` is false.

The logical NOT operator (`!`) is compiled to a branchless `EOR #1` sequence for Zero-Page and memory boolean variables.

### 2.3.5 Conditional Operator

The conditional (ternary) operator evaluates a boolean condition and selects between two expressions:

`condition ? expr_if_true : expr_if_false`

It uses short-circuit evaluation, executing only the chosen branch.

```c
char d = (c > 0) ? c : -c;
```

### 2.3.6 Comma Operator

The comma operator evaluates the left operand, discards its result, and then evaluates the right operand, returning its value:

`expr1 , expr2`

This is frequently used in `for` loops to update multiple index variables simultaneously:

```c
for (uint8_t i = 0, j = 0; i < 40; i++, j += 2) { ... }
```

### 2.3.7 Assignment Operators

Assignment is an expression returning the assigned value, enabling chained assignments (`a = b = 0;`). Compound assignment operators are shorthand for read-modify-write operations:

| Operator | Meaning |
| :--- | :--- |
| `a = b` | Direct assignment |
| `a += b` | Addition assignment (`a = a + b`) |
| `a -= b` | Subtraction assignment (`a = a - b`) |
| `a *= b` | Multiplication assignment |
| `a /= b` | Division assignment |
| `a %= b` | Modulo assignment |
| `a <<= b` | Left shift assignment |
| `a >>= b` | Right shift assignment |
| `a &= b` | Bitwise AND assignment |
| `a |= b` | Bitwise OR assignment |
| `a ^= b` | Bitwise XOR assignment |

Small 2..4 byte struct or union assignments unroll into direct inline `LDA/STA` pairs in Pass 5.

### 2.3.8 Increment and Decrement Operators

Pre- and post-increment/decrement operators increment or decrement numeric variables:

- `++a`: Pre-increment (increments variable, evaluates to new value)
- `--a`: Pre-decrement (decrements variable, evaluates to new value)
- `a++`: Post-increment (evaluates to original value, then increments variable)
- `a--`: Post-decrement (evaluates to original value, then decrements variable)

KickC generates 6502 `INC`/`DEC` instructions for memory variables and `INX`/`DEX`/`INY`/`DEY` for index registers.

### 2.3.9 Pointer and Array Operators

- `&a`: Address-of operator (returns pointer to variable)
- `*a`: Pointer dereference (reads or writes pointed memory)
- `a[b]`: Array indexing (equivalent to `*(a + b)`)

When operating in writable RAM segments, sequential pointer dereferences (`*ptr++ = val`) are automatically uplifted to direct self-modifying code operands (see Section 2.2.5).

### 2.3.10 Low/High Byte and Word Operators

KickC includes low/high byte operators familiar from 6502 assembly language:

- `<a`: Low-byte of a 16-bit word, or low-word of a 32-bit dword.
- `>a`: High-byte of a 16-bit word, or high-word of a 32-bit dword.

These operators can also appear on the left side of an assignment to modify only the low or high part of a variable without masking:

```c
uint16_t ptr = 0x0400;
<ptr = 0x28; // ptr becomes 0x0428 (compiled to direct STA ptr)
>ptr = 0x05; // ptr becomes 0x0528 (compiled to direct STA ptr+1)
```

### 2.3.11 Compile-Time Array Length Operator (`countof` - C2Y N3169)

KickC implements the upcoming ISO C2Y `countof()` compile-time array length operator:

```c
const uint8_t tiles[] = { 10, 20, 30, 40, 50 };
static_assert(countof(tiles) == 5);

for (uint8_t i = 0; i < countof(tiles); i++) {
    render_tile(tiles[i]);
}
```

Properties:
- Evaluates the element count at compile time without runtime instructions.
- Replaces the error-prone classic macro `#define countof(a) (sizeof(a)/sizeof(a[0]))`.
- **Rejects pointer types:** Calling `countof(ptr)` on a pointer produces a compile-time error (`CompileError`), which prevents out-of-bounds bugs caused by array-to-pointer decay.

### 2.3.12 Compile-Time Generic Type Dispatch (`_Generic` - C11)

KickC supports ISO C11 `_Generic` expressions to perform compile-time type matching:

```c
#define format_type(X) _Generic((X), \
    char: 1, \
    int: 2, \
    long: 3, \
    default: 0 \
)
```

In the KickC standard library, `<stdlib.h>` uses `_Generic` to provide a polymorphic `abs()` macro that selects between 8-bit `abs8()`, 16-bit `abs16()`, and 32-bit `labs()` at compile time.

### 2.3.13 Compile-Time Table Generator (`__builtin_lut`)

The `__builtin_lut(size, expression)` intrinsic generates constant lookup tables at compile time from a mathematical expression:

```c
#include <math.h>

// Populates a 256-byte sine table at compile time:
const uint8_t sin_table[256] = __builtin_lut(256, 127.5 + 127.5 * sin(i * 2 * PI / 256));

// 16-bit word table:
const uint16_t screen_rows[25] = __builtin_lut(25, 0x0400 + i * 40);
```

Within the expression, `i` represents the table index ($0 \dots \text{size}-1$). KickC delegates the evaluation to KickAssembler's built-in math engine (`.fill` / `.fillword`), removing the need for external generator scripts.

### 2.3.14 Atomic Status Intrinsics (`<6502.h>`)

KickC provides intrinsics for processor flag operations and for bit tests that do not clobber register A:

```c
#include <6502.h>

// Direct instruction intrinsics:
SEC(); // Set Carry flag (SEC)
CLC(); // Clear Carry flag (CLC)
CLD(); // Clear Decimal flag (CLD)
SED(); // Set Decimal flag (SED)
NOP(); // No Operation (NOP)

// Bit-test intrinsics without clobbering Register A:
if (__builtin_bit7(0xd011)) { ... } // Generates BIT $d011; BMI / BPL
if (__builtin_bit6(0xd011)) { ... } // Generates BIT $d011; BVS / BVC
```

### 2.3.15 Cycle-Exact Delay Synthesizer (`delay_cycles`)

Raster timing and hardware bit-banging frequently require exact cycle delays. The `delay_cycles(N)` intrinsic in `<6502.h>` generates a short instruction sequence that takes the specified number of cycles:

```c
#include <6502.h>

delay_cycles(8); // Synthesizes 4x NOP (8 cycles)
delay_cycles(7); // Synthesizes bit $00 (3 cycles) + 2x NOP (4 cycles)
```

The intrinsic is expanded inline (no `JSR`/`RTS`). Cycle counts refer to standard NMOS 6502 timing.

### 2.3.16 Function Calls

Functions returning values can be called as part of any expression: `uint8_t result = compute(x, y);`. Calling conventions and register-pair passing are detailed in Section 2.5.

### 2.3.17 Automatic Type Conversion and Type Casting

KickC evaluates operators using the natural width of the operands and avoids unnecessary integer promotions to 16-bit `int`. Limiting automatic promotions keeps the generated 8-bit assembly compact.

Type widening occurs automatically when an operand can safely hold all values of another type (e.g. `uint8_t` to `uint16_t`). Explicit casting `(type)val` forces conversion when precision might be narrowed:

```c
uint8_t low_byte = (uint8_t)(word_val >> 2);
```

KickC can often evaluate operators on operands of different types directly. For instance, adding a byte to a word requires less code than converting the byte to a word first. In practice, the difference to standard C rarely has consequences.

As in standard C, automatic conversion is performed if the target type can hold all values of the source type. For example, an `unsigned char` can be converted to a signed `int`, but not to a `signed char`.

| Type | Can be automatically converted to |
| --- | --- |
| `unsigned char` | `unsigned int`, `signed int`, `unsigned long`, `signed long` |
| `signed char` | `signed int`, `signed long` |
| `unsigned int` | `unsigned long`, `signed long` |
| `signed int` | `signed long` |
| `unsigned long` | - |
| `signed long` | - |

The cast operator `(type)a` performs explicit conversion, including conversions to types that cannot hold all values of the source type (e.g. `signed char` to `unsigned char`), where information may be lost.

For sub-expressions that contain only constants, the compiler infers the type by performing the calculation and checking which types can hold the result. For instance, `$4000/$80` matches every integer type except `signed char`, since a `signed char` cannot hold 128. This differs from standard C, where integer constants are `int` unless specified otherwise.

### 2.3.18 Operator Precedence and Associativity

Operators with higher precedence are applied first (e.g. multiplication before addition in `a*b+c`). Precedence can be overridden with parentheses, e.g. `(a+b)*c`. The following table summarizes operator precedence in KickC (highest precedence at the top):

| Precedence | Operators | Description | Associativity |
| :---: | :--- | :--- | :---: |
| 1 | `a++` `a--` `f()` `a[b]` `.` `->` | Postfix operators, calls, indexing, member access | Left-to-right |
| 2 | `++a` `--a` `+a` `-a` `~a` `!a` `(type)a` `*a` `&a` `countof` `_Generic` | Prefix increment/decrement, unary math, casts, dereference, address-of, compile-time operators | Right-to-left |
| 3 | `a * b` `a / b` `a % b` | Multiplicative | Left-to-right |
| 4 | `a + b` `a - b` | Additive | Left-to-right |
| 5 | `a << b` `a >> b` | Bitwise shifts | Left-to-right |
| 6 | `<a` `>a` | 6502 Low / High byte operators | Left-to-right |
| 7 | `a < b` `a <= b` `a > b` `a >= b` | Relational ordering | Left-to-right |
| 8 | `a == b` `a != b` | Relational equality | Left-to-right |
| 9 | `a & b` | Bitwise AND | Left-to-right |
| 10 | `a ^ b` | Bitwise XOR | Left-to-right |
| 11 | `a \| b` | Bitwise OR | Left-to-right |
| 12 | `a && b` | Logical AND | Left-to-right |
| 13 | `a \|\| b` | Logical OR | Left-to-right |
| 14 | `a ? b : c` | Conditional (ternary) | Right-to-left |
| 15 | `a = b` `a += b` `a -= b` `*=` `/=` `%=` `<<=` `>>=` `&=` `^=` `\|=` | Assignment and compound assignment | Right-to-left |
| 16 | `a , b` | Comma operator | Left-to-right |

## 2.4 Statements

The statements of a KickC program control the flow of execution. KickC supports standard C control flow, modern C2Y/C++17 statements with initializers, named loops, countdown loop inversion, and compile-time assertions.

Statements are separated by semicolons (`stmt;`) and can be grouped into blocks using curly braces (`{ stmt1; stmt2; }`).

### 2.4.1 Expressions and Assignments

Any expression followed by a semicolon is a valid statement. Typical examples include variable assignments, increment operations, and function calls:

```c
a += 2;
x++;
printf("hello world!\n");
```

### 2.4.2 If and If with Initializers (C2Y / C++17)

The body of an `if` statement executes only when the condition evaluates to true. An optional `else` branch executes when the condition is false:

```c
if ((a & 1) == 0) {
    print("even");
} else {
    print("odd");
}
```

#### If-Statements with Initializers (C2Y / C++17)

KickC implements the modern `if` statement with an initializer:

```c
if (uint8_t d = get_distance(); d < 10) {
    handle_collision(d);
} else {
    log_safe(d);
}
```

The declared variable (`d`) is scoped to the `if` and `else` blocks. This allows KickC's SSA live-range analyzer to reuse that Zero-Page address for subsequent code, reducing overall Zero-Page usage.

### 2.4.3 Switch and RTS Jump Tables

The `switch` statement dispatches execution to one of several constant cases:

```c
switch (c) {
    case 0:
        return;
    case '\t':
    case '\n':
        print(' ');
        break;
    default:
        print(c);
        break;
}
```

#### Switch-Statements with Initializers (C2Y / C++17)

Variables can be initialized directly inside the `switch` header:

```c
switch (uint8_t cmd = fetch_command(); cmd) {
    case CMD_ATTACK:  do_attack(); break;
    case CMD_DEFEND:  do_defend(); break;
    default:          break;
}
```

#### Switch Jump Tables via RTS Dispatch

Pass 5 replaces a linear `CMP`/`BEQ` chain with a split Lo/Hi jump table dispatched via the 6502 `RTS` trick. The transformation is applied when the chain has at least 4 comparisons, the cases have at least 3 distinct targets, all case values are in the range 0..255, the value range (max - min + 1) is at most 32, and at least half of the values in that range are used:
```asm
tax
lda jump_table_hi, x
pha
lda jump_table_lo, x
pha
rts
```
The dispatch takes a constant time of about 20 cycles (plus the range check), independent of the case value. Switches that do not meet the conditions above keep the linear comparison chain.

### 2.4.4 While

A `while` loop evaluates its condition before executing the loop body. If the condition is false initially, the body is never executed:

```c
while (i != 0) {
    print(".");
    i--;
}
```

### 2.4.5 Do-While

A `do-while` loop executes its body first, then evaluates the condition. The body is always executed at least once:

```c
do {
    keyboard_event_scan();
} while (keyboard_event_get() != KEY_SPACE);
```

### 2.4.6 For, Ranged Loops, and __countdown

A standard `for` loop has the syntax:

```c
for (init; condition; increment) {
    body;
}
```

#### Interval Ranged For-Loops

KickC provides a syntax for looping over constant integer intervals:

```c
for (char i : 0..127) {
    body;
}
```

This is equivalent to `for(char i = 0; i <= 127; i++)` and can loop forward or backward.

#### Countdown Loops & the `__countdown` Directive

Counting down to zero on the 6502 can allow branching on the Zero or Negative flags (`BNE`, `BPL`) without an explicit comparison instruction (`CPX`/`CPY`).

The `__countdown` directive can be prefixed to a loop to instruct the compiler to invert iteration order:

```c
__countdown for (uint8_t i = 0; i < 40; i++) {
    screen[i] = ' ';
}
```

The compiler transforms this into a loop that counts down from 39 to 0 and branches with `BPL`. Whether the compiler can drop the explicit comparison depends on the surrounding code.

### 2.4.7 Break, Continue, and Named Loops (C2Y N3355)

The `break` statement exits the innermost loop or switch. The `continue` statement skips to the next iteration of the innermost loop.

#### Named Loops (C2Y N3355)

When loops are deeply nested or contain `switch` statements, KickC allows prefixing loops with a label to break or continue an outer loop directly:

```c
char found = 0;
outer_loop: for (uint8_t y = 0; y < 25; y++) {
    for (uint8_t x = 0; x < 40; x++) {
        if (screen[y * 40 + x] == '*') {
            found = 1;
            break outer_loop; // Jumps directly out of both loops
        }
    }
}
```

This avoids auxiliary flag variables or `goto`.

### 2.4.8 Goto and Labels

The `goto` statement performs an unconditional jump to a labeled statement within the same function:

```c
label_name:
    // ...
    goto label_name;
```

In accordance with standard C, labels have function scope. Jumps within -128 to +127 bytes use native 6502 branch opcodes; longer branches are relaxed to reverse-condition jumps in Pass 5.

### 2.4.9 Compile-Time Assertions (`static_assert` - C11 / C23)

KickC implements `static_assert` (and `_Static_assert`) from C11 and C23. It validates memory layouts, structure sizes, and constant invariants at compile time:

```c
static_assert(sizeof(char) == 1);
static_assert(sizeof(struct Actor) <= 32, "Actor structure size exceeded 32 bytes!");
static_assert(countof(font_tiles) == 64);
```

If the assertion expression evaluates to false (or 0), KickC aborts compilation with a `CompileError`. Compile-time assertions generate no code and use no memory.

## 2.5 Functions

Functions are named blocks of reusable code. KickC analyzes the global call graph to statically allocate registers and Zero-Page memory, so no runtime stack frame is used.

### 2.5.1 Calling Functions

A function is called using standard C syntax: `result = name(param1, param2);`.

```c
char m = max(31, 9);
char n = max(m, max(47, 7));
```

### 2.5.2 Creating Functions

Functions are declared with return type, identifier, parameter list, and body:

```c
char max(char a, char b) {
    if (a > b) return a;
    return b;
}
```

Functions returning no value declare return type `void`. The `return` statement immediately exits the function and optionally returns a value.

### 2.5.3 Calling Conventions

KickC supports multiple calling conventions to balance speed, memory, and hardware register usage:

- **`__phicall` (Default):** Static SSA-based parameter and return passing. Parameters are allocated to CPU registers (`A`, `X`, `Y`) or Zero-Page locations based on whole-program live-range analysis. This convention does not use the stack.
- **`__ax` 16-Bit Register Pair Calling Convention:** Enables standard 6502 16-bit register pair passing:
  - 16-bit return values are returned in accumulator `A` (low byte) and index register `X` (high byte).
  - Single 16-bit parameters are received directly in `A` (low) and `X` (high), which removes the Zero-Page setup at the call site.
  - Allows chaining `f(g(x))` without intermediate memory operations, since the result of `g` is already in `A`/`X`.
  ```c
  __ax uint16_t get_screen_offset(uint16_t coord) {
      return coord + 0x0400;
  }
  ```
- **`__stackcall`:** Parameters are passed on the 6502 hardware stack (`PHA`/`PLA`).
- **`__varcall`:** Parameters are passed through static global variables in main memory.

### 2.5.4 Auto Carry-Flag Boolean Returns (`__carry`)

On the 6502, conditions are tested in the processor status flags. Converting a condition to a `0` or `1` byte in `A` and comparing it at the call site costs additional cycles and memory.

Functions can be marked with `__carry` (or are automatically optimized by Pass 5) to return boolean status directly in the processor Carry flag:

```c
__carry bool is_in_range(uint8_t val, uint8_t min, uint8_t max) {
    return (val >= min && val <= max);
}

void test(uint8_t x) {
    if (is_in_range(x, 10, 50)) { // Compiles directly to JSR is_in_range; BCS in_range!
        on_valid();
    }
}
```

The function exits with `SEC; RTS` (true) or `CLC; RTS` (false), and the caller branches directly with `BCS` or `BCC` without using the accumulator.

### 2.5.5 Banked Functions (`__bank`)

For systems with banked memory (e.g. cartridge ROMs or RAM expansions), a function can be assigned to a bank with the `__bank(area, number)` directive. The function is placed in the segment selected by `#pragma code_seg`:

```c
#pragma code_seg(RAM_Bank1)
__bank(cx16_ram, 1) char plus(char a, char b) {
    return a + b;
}
```

Depending on the banks of caller and callee, the compiler classifies each call as *near* (same bank or unbanked), *close* (unbanked to banked), or *far* (different bank in the same bank area), and generates the corresponding bank-switching code. The bank areas and segments must be defined in the linker script. The compiler includes call fragments for the bank areas `cx16_ram` and `cx16_rom`; other bank area names fail with a missing fragment error (e.g. `call_phi_close_<area>`) unless a matching fragment is provided. See the `call-banked-*` programs in the compiler test suite for examples.

### 2.5.6 The main() Function

Every KickC program must define exactly one `void main()` function as the entry point:

```c
#include <c64.h>

void main() {
    *BGCOL = BLACK;
}
```

For Commodore platforms, KickC automatically emits a BASIC upstart stub (`10 SYS 2061`) initiating execution at `main`.

The following KickAssembler code is generated for the program above. `BasicUpstart` creates a BASIC program with a single `SYS` command.

```
.pc = $801 "Basic"
:BasicUpstart(main)
.pc = $80d "Program"
  .label BGCOL = $d021
  .const BLACK = 0
main: {
    lda #BLACK
    sta BGCOL
    rts
}
```

## 2.6 Comments

KickC supports standard C comments:

```c
// Single-line comment

/* Multi-line
   block comment */
```

## 2.7 Splitting Code into Multiple Files

Code can be organized into header files (`.h`) and implementation files (`.c`):

```c
#include "graphics/sprites.h"
#include <stdio.h>
```

Search order:
1. Current folder of the including file (for `"..."`).
2. Include directories specified via `-I` or `-includedir`.
3. Standard KickC library folder.

Unlike conventional compilers that compile `.c` files independently into `.o` object files and link them afterwards, KickC compiles all source files simultaneously as a whole program. This whole-program compilation allows global SSA optimizations, dead code elimination, and cross-module Zero-Page coalescing:

```pwsh
kickc.bat main.c graphics.c sound.c
```

KickC tracks included files and ensures that each header or source file is parsed exactly once.

Using `#include <file.h>` instead of `#include "file.h"` skips the current folder and searches only the include folders, which is useful for libraries.

When a header file is included, the compiler tries to locate the matching C file (same name, extension `.c`) in the current folder and in each library folder added with `-L / -libdir`. Because every file is included only once and all files are compiled together, header files can be omitted and C files included directly. This is convenient if the code does not need to be ported to other C compilers.

## 2.8 Variable Directives

Variable directives control memory placement, alignment, and optimization.

### 2.8.1 Const

Declares variables that cannot be modified by the program. The declaration must include an initializer, and the compiler reports an error if the variable is assigned elsewhere. For pointers, the position of `const` relative to `*` selects between a constant pointer and a pointer to constant data:
- `const uint8_t MAX_PLAYERS = 4;`
- `char* const SCREEN = (char*)0x0400;` (constant pointer to mutable data)
- `const char* ROM = (char*)0xa000;` (mutable pointer to constant data)

### 2.8.2 Register

Requests placement in a CPU register (`A`, `X`, or `Y`):

```c
register(X) uint8_t i;
```

KickC's register allocator automatically places frequently used loop and index variables into CPU registers, so the `register` directive is rarely required. Without a register name (`register i;`) the variable is merely optimized for register placement. With a register name the variable is forced into that register, and compilation can fail if the program cannot be compiled with that assignment.

### 2.8.3 Align (`__align`)

Aligns array data to a specific byte boundary in memory (e.g. page boundary `0x100`):

```c
__align(0x100) uint8_t sine_table[256];
```

### 2.8.4 Address (`__address`)

Binds a global variable or buffer to a specific physical memory address:

```c
__address(0xd020) volatile uint8_t BORDER_COLOR;
__address(0x2000) uint8_t SPRITE_BITMAPS[64 * 8];
```

Only global variables and arrays can be placed at specific addresses. `__address()` is not supported for local arrays. For graphics data, inline KickAssembler can initialize an array directly from an image file (see Section 2.9.9).

### 2.8.5 Zero-Page Allocation (`__zp`)

Explicitly forces variable storage into Zero-Page memory:

```c
__zp uint8_t* ptr;
__zp uint16_t zx02_offset;
```

Use this for variables that must reside on Zero-Page and must not be placed in main memory or clobbered by register allocation, e.g. decompression pointers or raster interrupt counters.

### 2.8.6 Main Memory Allocation (`__mem`)

Explicitly forces variable storage into Main Memory (`Data` segment):

```c
__mem uint8_t background_cache[64];
```

Prevents the variable from being placed in Zero-Page, which keeps Zero-Page space available for inner loops and pointers.

### 2.8.7 Volatile

Informs the compiler that a variable can be modified externally (e.g. hardware I/O registers or variables shared with interrupt handlers). Prevents caching in registers or eliminating redundant accesses:

```c
volatile uint8_t raster_line;
```

For pointers, the position of `volatile` relative to `*` distinguishes a volatile pointer from a pointer to volatile data (see `const`). Variables shared between interrupt handlers and the main program must be declared `volatile`.

### 2.8.8 Extern and Export (`__export`)

- `extern`: Declares a variable that is defined elsewhere. Typically used in header files for variables defined in a C file.
- `__export`: Prevents the optimizer from removing a global data variable (typically an array) that is never referenced, e.g. binary tables intended for external tools.

### 2.8.9 Split Word Arrays (`__lohi`)

Splits a 16-bit word array up to 256 elements into parallel low- and high-byte tables in memory:

```c
__lohi uint16_t offsets[128];
```

Lowers indexing to 8-cycle indexed absolute instructions without 16-bit pointer math (see Section 2.2.6).

### 2.8.10 Struct-of-Arrays Flattening (`__soa`)

Automatically transposes an array of structures up to 256 elements into parallel column arrays in memory:

```c
__soa struct Actor actors[32];
```

Member accesses compile to single 4-cycle indexed operations (`lda actors_x, x`), eliminating runtime struct size multiplication (see Section 2.2.7).

### 2.8.11 Packed 4-Bit Nibbles (`__nibble` / `uint4_t`)

Packs 4-bit elements pairwise into bytes in memory:

```c
uint4_t nybble_buffer[500]; // Allocates 250 bytes
```

### 2.8.12 Binary Coded Decimal (`__bcd`)

Declares variables intended for hardware BCD arithmetic with `<bcd.h>`.

## 2.9 Function Directives

### 2.9.1 Inline

Instructs the compiler to inline the function body at every call site, eliminating `JSR`/`RTS` overhead and enabling call-specific constant folding:

```c
inline uint8_t clamp(uint8_t val, uint8_t min, uint8_t max) {
    if (val < min) return min;
    if (val > max) return max;
    return val;
}
```

### 2.9.2 Interrupt (`__interrupt`)

Declares an interrupt service routine:

```c
__interrupt void irq_handler() {
    *BORDER = 1;
    *BORDER = 0;
}
```

Optional parameters specify the interrupt entry/exit convention:
- `rom_min_c64`: C64 Kernal interrupt via `$0314/$0315`, exits via Kernal `$ea81` (Default for C64).
- `rom_sys_c64`: C64 Kernal interrupt, exits via `$ea31` (runs keyboard scan and system timer).
- `rom_min_vic20`: VIC-20 Kernal interrupt via `$0314/$0315`, exits via `$eb18` (Default for VIC-20).
- `rom_sys_vic20`: VIC-20 Kernal interrupt, exits via `$eabf` (runs keyboard scan and system timer).
- `rom_min_plus4`: Plus/4 Kernal interrupt via `$0314/$0315`, exits via `$fcc3` (Default for Plus/4).
- `rom_sys_plus4`: Plus/4 Kernal interrupt, exits via `$ce0e` (runs the normal Kernal service routine, including keyboard handling).
- `rom_min_cx16`: CX16 Kernal interrupt via `$0314/$0315`, exits via `$e049` (Default for CX16).
- `rom_sys_cx16`: CX16 Kernal interrupt, exits via `$e034` (runs the normal Kernal service routine, including keyboard handling).
- `hardware_all`: Hardware vector interrupt (`$fffe`/`$fffa`), saves and restores all registers (`A`, `X`, `Y`) and exits with `RTI`.
- `hardware_clobber`: Saves/restores only registers clobbered by the handler body, exits with `RTI`.
- `hardware_none`: Saves no registers, exits with `RTI`.

The hardware interrupt types do not depend on code in ROM and can be used on all platforms. They are the default for Atari 2600, Atari XL/XE, and NES. If no type is specified, the default type of the platform (or the type set with `#pragma interrupt`) is used.

Interrupts are set up by assigning a pointer to the handler to an interrupt vector at a fixed memory address. The following C64 example sets the Kernal IRQ vector (`$0314`) to `irq_handler()`. The setup code should be surrounded by `SEI`/`CLI` to prevent interrupts during the setup:

```c
#include <c64.h>
#include <6502.h>

void main() {
    SEI();
    *KERNEL_IRQ = &irq_handler;
    CLI();
}
```

Global variables used to communicate with the main program or to keep state between interrupt calls must be declared `volatile`.

The `hardware_all` entry code executes `CLD` to ensure binary arithmetic mode, so decimal mode left enabled by the interrupted code (see `<bcd.h>`) does not affect the handler.

### 2.9.3 16-Bit Register Calling Convention (`__ax`)

Forces 16-bit argument passing and return value in the `A`/`X` register pair:

```c
__ax uint16_t compute_ptr(uint16_t base);
```

### 2.9.4 Auto Carry-Flag Returns (`__carry`)

Returns boolean results directly in the 6502 Carry status flag (`SEC; RTS` / `CLC; RTS`):

```c
__carry bool is_collision(uint8_t x, uint8_t y);
```

### 2.9.5 Countdown Loop Inversion (`__countdown`)

Instructs the compiler to invert loop counting to count down toward zero, allowing `BNE` or `BPL` branching without comparison instructions:

```c
__countdown for (uint8_t i = 0; i < 40; i++) { ... }
```

### 2.9.6 Banked Functions (`__bank`)

Assigns a function to a bank: `__bank(area, number)` (see Section 2.5.5).

```c
__bank(cx16_ram, 1) char plus(char a, char b);
```

### 2.9.7 Inline Assembler Code (`asm`)

Inline assembly allows direct 6502/65C02/65CE02/45GS02 machine instructions inside function bodies:

```c
asm {
    sei
    lda #$7f
    sta $dc0d
}
```

Features:
- Supports all standard addressing modes (immediate, absolute, zero page, indexed, indirect, relative).
- Supports NMOS-6510 illegal opcodes (`LAX`, `SAX`, `ALR`, `DCP`, `ISC`) when compiling with target CPU `mos6502x` or `rom6502x`.
- References C constant symbols directly: `sta SCREEN+40`.
- The `clobbers` clause specifies the modified registers: `asm(clobbers "AX") { jsr $1000 }`. If the inline assembler contains a `JSR` and no `clobbers` clause is given, the compiler assumes that all registers are clobbered.
- Labels declared in inline assembler of other functions can be referenced with the `.` operator (e.g. `clearscreen.fillchar`). This allows assembler in one function to modify assembler code in another scope.
- The compiler analyzes the inline assembler and includes it in its optimizations. It determines the clobbered registers and may modify the assembler code if this is faster, e.g. by removing an immediate load of a value that the register is already known to contain.

Supported syntax (same as KickAssembler and most classic 6502 assemblers):
- 6502/6510 instructions and addressing modes: immediate `lda #%10101010`, absolute `eor 1024`, zero page `rol 2`, relative `bne nxt`, absolute indexed `adc $2000,x` / `cmp sintab,y`, zero page indexed `sbc 2,x` / `stx $fe,y`, indexed indirect `lda ($20,x)`, indirect indexed `ora (14),y`, indirect `jmp ($1000)`, implied `tax`.
- 65CE02: indirect zero page indexed by Z `lda ($12),z`, stack pointer indirect indexed `sta ($12,sp),y`, immediate word `phw #$1234`, long relative `lbra #$1234`.
- 45GS02: 32-bit indirect zero page Z `lda (($12)),z` and 32-bit indirect zero page `stq (($12))`.
- Labels: normal labels `next:` and multi labels `!next:`. Data: `.byte $10, $20`.
- Operand expressions: numeric literals (`1024`, `$3fff`, `%10101010`), character literals (`'q'`), constants declared in C, labels declared in the assembler code, and the operators `+ - * / < > << >>`. Use `[` and `]` for grouping to avoid interpretation as indirect addressing.

### 2.9.8 Inline KickAssembler Code (`kickasm`)

Allows embedding arbitrary KickAssembler macros, directives, loops, and binary imports into the compilation stream:

```c
void fast_clear(char fill) {
    kickasm(uses fill) {{
        lda fill
        .for (var i = 0; i < 1000; i++) {
            sta $0400 + i
        }
    }}
}
```

The `uses` clause notifies KickC that a C symbol is referenced, preventing dead-code elimination.

The body of `kickasm` must be enclosed in double curly braces and is passed directly to KickAssembler. KickC does not parse or analyze it. Use `kickasm` for features not supported by inline `asm`, such as macros, loops, or importing binary files and images. See the [KickAssembler manual](http://theweb.dk/KickAssembler).

### 2.9.9 KickAssembler Data Array Initialization

KickAssembler directives can initialize static constant arrays:

```c
const uint8_t sine_lut[256] = kickasm {{
    .fill $100, 127.5 + 127.5 * sin(i * 2 * PI / 256)
}};
```

To place the data at a specific address, combine it with `__address()` (global variables only):

```c
__address(0x1000) char sintab[] = kickasm {{
    .fill $100, 127.5 + 127.5*sin(toRadians(i*360/256))
}};
```

When the KickAssembler code loads binary files (pictures, music, ...), the files must be declared with a `resource` directive so that KickC can copy them to the output directory. The following example creates sprite data from a PNG file and places it at a fixed address:

```c
__address(0xc00) char SPRITE[] = kickasm(resource "balloon.png") {{
    .var pic = LoadPicture("balloon.png", List().add($000000, $ffffff))
    .for (var y=0; y<21; y++)
        .for (var x=0; x<3; x++)
            .byte pic.getSinglecolorByte(x, y)
}};
```

## 2.10 Controlling the Compilation

KickC provides several mechanisms to control code generation, memory layout, and optimization, both via `#pragma` directives in source files and via command-line options. `#pragma` directives have the advantage that the compilation options are documented in the source code.

### 2.10.1 Target Platform

You can select the target platform via `#pragma target(name)` or command-line options `-t name` / `-p name`. A target platform bundles a linker file and other compilation options and defines the default emulator used to execute the binaries. Platforms are defined by `.tgt` files in the `target` folder of the KickC installation.

Built-in platforms:
- `c64`: Commodore 64 (Default)
- `vic20`: Commodore VIC-20
- `plus4`: Commodore Plus/4 (also C16 / C116)
- `atari2600`: Atari 2600
- `atarixl`: Atari XL/XE
- `nes`: Nintendo Entertainment System
- `mega65`: MEGA65
- `cx16`: Commander X16

### 2.10.2 Linker Specification

Custom memory configurations and binary formats are specified using `#pragma link("filename.ld")` or `-T filename.ld`. Linker files use the KickAssembler segment syntax.

KickC expands the following macro variables in linker scripts:
- `%E`: Entry point symbol (e.g. `main` or `__start`).
- `%P`: Program start address in memory (see `#pragma start_address`).
- `%O`: Target binary output filename.
- `%_O`: Lowercase output filename.
- `%^O`: Uppercase output filename.

The `.ld` files in the `target` folder of the KickC installation can be used as a starting point for custom linker specifications. Some example programs use custom linker files, e.g. `kernalload.c` generates a D64 disk image.

### 2.10.3 Code and Data Segments

By default, code is placed in segment `"Code"` and data in `"Data"`. A custom linker specification can declare additional named segments that are written to the binary. The destination segment is selected with `#pragma code_seg(name)` and `#pragma data_seg(name)`, which take effect when encountered and apply to all following code or data until changed:

```c
#pragma code_seg(MusicEngine)
void play_sound() { ... }

#pragma data_seg(MusicData)
const uint8_t song[] = { ... };

// Restore default segments:
#pragma code_seg(Code)
#pragma data_seg(Data)
```

### 2.10.4 Segment Type Classification & ROM Safety (`#pragma segment_type`)

On banked systems (such as cartridges or RAM expansions), writing to ROM or calling directly across banks without a trampoline causes runtime failures. KickC can detect these cases at compile time via `#pragma segment_type`:

```c
#pragma segment_type(Code, RAM_RESIDENT)
#pragma segment_type(Bank1, ROM_BANK, 1)
#pragma segment_type(Bank2, ROM_BANK, 2)
#pragma segment_type(SharedOverlay, RAM_OVERLAY)
```

Supported segment classifications:
- `ROM`: Immutable read-only memory.
- `ROM_BANK`: Banked read-only memory with optional integer bank index.
- `RAM_RESIDENT` (or `RAM`): Resident writable memory.
- `RAM_OVERLAY`: Dynamically loaded or shared RAM overlay area.

Enforced compiler protections:
1. **ROM-Write Protection:** The compiler rejects any runtime write access or non-const variable allocation within a `ROM` or `ROM_BANK` segment with a hard `CompileError`.
2. **Cross-Bank Call Protection:** Direct function calls between different `ROM_BANK` segments without a banking trampoline are detected in Pass 1 and rejected at compile time.

### 2.10.5 Predefined Scratchpad Segment: `StackRam` (`$0100-$01AF`)

The MOS 6502 hardware stack resides in page 1 (`$0100-$01FF`). Programs typically use only part of this page. KickC's linker scripts predefine the lower 176 bytes (`$0100-$01AF`) as the `StackRam` segment.

Scratchpad buffers or decompression workspaces can be placed directly into `StackRam` to save resident main RAM:

```c
#pragma data_seg(StackRam)
uint8_t fast_decomp_buffer[128];
#pragma data_seg(Data)
```

### 2.10.6 Banking (`#pragma bank`, `#pragma nobank`)

`#pragma bank(area, number)` assigns the functions and variables that follow to the given bank; `#pragma nobank` returns to the common (unbanked) memory. Individual functions can be assigned with the `__bank(area, number)` directive (see Section 2.5.5). The segment of the code is selected separately with `#pragma code_seg`. Bank areas are defined by the linker script and require matching call fragments (see Section 2.5.5).

### 2.10.7 Loop Alignment (`#pragma loop_align`)

MOS 6502 branch instructions (`BNE`, `BEQ`, `BCC`, `BCS`, etc.) take 3 CPU cycles if the branch is taken within the same page, but take 1 additional cycle (4 in total) when the branch crosses a 256-byte page boundary. In tight inner loops this adds execution time and timing jitter.

`#pragma loop_align` instructs Pass 5 to automatically insert conditional KickAssembler alignment (`.if ((* & $ff) > (256-L)) .align $100`) for loops smaller than the specified byte threshold:

```c
#pragma loop_align(32)   // Align inner loops up to 32 bytes to page boundaries
#pragma loop_align(on)   // Enable with default threshold of 32 bytes
#pragma loop_align(off)  // Disable loop alignment
```

Unlike fixed `.align $100` padding, KickC's conditional loop alignment only inserts padding bytes if the loop body would actually straddle a page boundary, which avoids wasting memory. Can also be enabled globally via the command-line option `-Oloop-align=<N>`.

### 2.10.8 Target CPU

Selects the instruction set and opcode model via `#pragma cpu(name)` or `-cpu name`:

| CPU Name | Target Description |
| :--- | :--- |
| `rom6502` | Standard MOS 6502 running in ROM (strictly official opcodes, no self-modifying code). |
| `rom6502x` | MOS 6502 running in ROM with stable undocumented/illegal opcodes (`LAX`, `SAX`, `ALR`, `DCP`, `ISC`). |
| `mos6502` | Standard MOS 6502 running in RAM (official opcodes, allows pointer-to-SMC optimization). |
| `mos6502x` | MOS 6502 running in RAM (Default: undocumented opcodes + RAM self-modifying code optimization). |
| `wdc65c02` | WDC 65C02 (adds `BRA`, `STZ`, `PHX`, `PLX`, `PHY`, `PLY`, zero-page indirect addressing). |
| `csg65ce02` | CSG 65CE02 (adds 16-bit branches, Z-register indexed indirect, stack relative). |
| `mega45gs02` | MEGA65 45GS02 (adds 32-bit Q registers, 32-bit indirect Z addressing). |

Support for the 65CE02 and 45GS02 CPUs requires a modified version of KickAssembler (available [here](https://gitlab.com/jespergravgaard/kickassembler65ce02)) until KickAssembler officially supports these CPUs.

### 2.10.9 Character Encoding & Custom Charmaps

Set the global string/character literal encoding via `#pragma encoding(name)`:
- `petscii_mixed`, `petscii_upper`: Commodore PETSCII.
- `screencode_mixed`, `screencode_upper`: Commodore screen codes (`screencode_mixed` is the default for VIC-20, C64, Plus/4, and MEGA65).
- `atascii`, `screencode_atari`: Atari character and screen codes (`atascii` is the default for Atari 2600, `screencode_atari` for Atari XL/XE).
- `ascii`: Standard ASCII.

#### Custom Encodings via `#pragma charmap()`
Custom encodings override specific characters of an existing base encoding: `#pragma charmap(custom_name, base_encoding, "mapping_rules")`. Each rule maps a source character to a target byte value, given in decimal, hexadecimal (`0x..`), or as a character literal (`'..'`). Rules can be separated by commas, semicolons, spaces, or newlines.

```c
#pragma charmap(german, ascii, "ä=27, ö=28, ü=29, Ä=91, Ö=92, Ü=93, ß=94")
#pragma encoding(german)
```

Individual string or character literals can also specify an explicit encoding suffix:

```c
const char greeting[] = "Grüße"german;
const char screen_str[] = "HELLO"screencode_upper;
const char raw_str[] = "NON-ZERO"asciiz; // "z" appended to the encoding name: no terminating '\0'
const char unterminated[] = "World"germanz;  // Same for custom encodings
char c = 'ä'german;                       // Character literal with encoding suffix
```

### 2.10.10 Binary Resource Embedding (`#embed` - C23)

Embeds raw binary files directly into array initializers at compile time:

```c
const uint8_t sprite_data[] = {
    #embed "assets/player.bin"
};

#if __has_embed("assets/tiles.bin")
const uint8_t tiles[] = {
    #embed "assets/tiles.bin" limit(1024)
};
#endif
```

### 2.10.11 Interrupt Type

Sets the interrupt type used by `__interrupt` functions that do not specify a type in parentheses, via `#pragma interrupt(type)` (see Section 2.9.2 for the available types):
`rom_min_c64`, `rom_sys_c64`, `hardware_all`, `hardware_clobber`, `hardware_none`.

### 2.10.12 Program Start Address

Specifies the program start address via `#pragma start_address(address)` (e.g. `#pragma start_address(0x0900)`; on targets with a BASIC upstart stub the address must not overlap the stub at `$0801`). The address is passed to the linker script, which uses it to place the program. The default start address of each platform is defined in its `.tgt` file.

### 2.10.13 Zeropage Address Reservations

Reserves Zero-Page addresses to prevent the compiler's register allocation from using them, e.g. when they are used by the Kernal, BASIC, or external assembly routines. Ranges are written as `from..to`:

```c
#pragma zp_reserve(0x02..0x03, 0x14..0x15, 0x90)
```

### 2.10.14 Struct Memory Model (`#pragma struct_model`)

Configures how structures are represented internally by the compiler:

```c
#pragma struct_model(unwind)   // Default: unwinds struct fields into individual SSA variables
#pragma struct_model(classic)  // Classic: keeps structures contiguous in memory for pointer arithmetic
```

- `unwind` (Default): Unwinds structure members into separate independent scalar variables. Allows register uplift and Zero-Page coalescing, which speeds up member accesses.
- `classic`: Preserves standard C contiguous layout in memory, necessary when passing pointers to raw structs to external assembly code or reinterpreting buffers.

Can also be set globally via `-struct_model=<unwind|classic>`.

### 2.10.15 Default Calling Convention (`#pragma calling`)

Sets the default calling convention for subsequent functions within the compilation unit:

```c
#pragma calling(__phicall)   // Fast static variable/register calling convention (default)
#pragma calling(__stackcall)  // Parameters passed on the hardware stack
#pragma calling(__varcall)    // Static variable calling convention
```

### 2.10.16 External Resource Files (`#pragma resource`)

Declares external resource files (such as music, tilemaps, or sprite binaries) that must be tracked and automatically copied into the target output directory during build:

```c
#pragma resource("assets/sid/music.sid")
#pragma resource("assets/tiles.chr")
```

### 2.10.17 Variable Model Configuration (`#pragma var_model`)

Controls memory placement and optimization strategies for variable categories:

```c
#pragma var_model(ssa_zp)    // Default: SSA variables allocated to Zero-Page registers
#pragma var_model(ssa_mem)   // Allocate SSA variables to main memory
```

Parameters follow the pattern `[scope]_[type]_[optimization]_[memoryarea]`.

### 2.10.18 Emulator & Output Extension

- `#pragma emulator("command")`: Overrides the emulator command used with `-e`. The command receives the generated binary as argument. The default is defined per platform in the `.tgt` file.
- `#pragma extension("ext")`: Overrides the output binary file extension (e.g. `"crt"`, `"prg"`, `"d64"`). The default is defined per platform in the `.tgt` file.

### 2.10.19 Library Init Code (`#pragma constructor_for`)

Used by library code to register an initialization function that must run before `main()` if specific library procedures are used. The first argument is the init function, followed by the procedures that require it:

```c
#pragma constructor_for(init_screen, print, print_char)
```

The compiler adds a call to `init_screen()` before `main()` for every program that uses `print` or `print_char`. If none of them is used, `init_screen()` is not called, and the optimizer removes it unless it is called elsewhere.

## 2.11 Standard Libraries

KickC includes a runtime library for 8-bit systems:

### Standard ISO C Headers

- **`<stdint.h>`:** Standard exact-width integer types (`uint8_t`, `int8_t`, `uint16_t`, `int16_t`, `uint24_t`, `int24_t`, `uint32_t`, `int32_t`).
- **`<stdbool.h>`:** Boolean type `bool`, `true` (1), and `false` (0).
- **`<limits.h>`:** Standard limits macros (`UCHAR_MAX`, `SCHAR_MIN`, `SCHAR_MAX`, `USHRT_MAX`, `SHRT_MIN`, `SHRT_MAX`, `UINT_MAX`, `INT_MIN`, `INT_MAX`, `ULONG_MAX`, `LONG_MIN`, `LONG_MAX`).
- **`<ctype.h>`:** Character classification and conversion routines (`isalpha`, `isdigit`, `isspace`, `isupper`, `islower`, `toupper`, `tolower`).
- **`<string.h>`:** String and memory routines (`memcpy`, `memcpy_fast`, `memset`, `memset_fast`, `strlen`, `strcpy`, `strcat`, `strcmp`, `strncmp`, `strchr`, `strrchr`).
- **`<stdlib.h>`:** General utilities (`abs` polymorphic via `_Generic`, `abs8`, `abs16`, `labs`, `rand`, `srand`, `rand8`, `srand8`, `rand32`, `srand32`, `uctoa`, `utoa`, `ultoa`, `atoi`, `malloc`, `calloc`, `free`, `bsearch16u`).
- **`<stdio.h>`:** Formatted output (`printf`, `sprintf`) with specialized 8-bit and 16-bit code paths. `<sprintf.h>` provides `sprintf_init` and `sputc`.
- **`<math.h>`:** Math utilities (`PI`, `M_PI`, `min`, `max`, `clamp`, `lerp8`, `popcount8`). Related headers: `<sqr.h>` (`sqr`, `sqrt16`, `sqrt`), `<division.h>` (`div8u`, `div16u`, ...), `<sine.h>` (sine table generators), `<atan2.h>`.

### Platform and Utility Headers

- **`<6502.h>`:** Hardware intrinsics (`SEC`, `CLC`, `CLD`, `SED`, `NOP`, `__builtin_bit7`, `__builtin_bit6`, `delay_cycles(N)`).
- **`<multiply.h>`:** Multiplication routines:
  - `fast_mul8u(a, b)`: Quarter-square table multiplication.
  - `fast_mul16u(a, b)`: 16-bit unsigned multiplication.
  - `fast_mul16s(a, b)`: 16-bit signed multiplication.
- **`<fixedpoint.h>`:** Fixed-point types, constants, and math functions (`fixed_add`, `fixed_mul`, `fixed_lerp`, `fixed_sin`, `fixed_cos`, ...).
- **`<bcd.h>`:** Hardware Binary Coded Decimal math (`bcd8_t`, `bcd16_t`, `bcd8_add`, `bcd8_sub`, `bcd16_add`, `bcd16_sub`).
- **`<rle.h>`:** PackBits RLE compression and decompression.
- **`<zx02.h>`:** ZX02 decompression.
- **`<c64-fade.h>`:** Color palette fading based on luminance.
- **`<c64-disk.h>`:** KERNAL disk and IEC bus I/O (`disk_probe`, `disk_load`, `disk_save`, `disk_send_command`, `disk_read_status`).
- **`<c64-fastio.h>`:** Fast disk I/O based on the Covert Bitops loader system (`fastio_init`, `fastio_open`, `fastio_load`, `fastio_save`, ...).
- **`<c64-mouse.h>`:** Commodore 1351 mouse driver with joystick fallback (`mouse_init`, `mouse_poll`).
- **`<reu.h>`:** Commodore 17xx RAM Expansion Unit DMA transfer routines.

## 2.12 Comparison with Standard C

KickC is designed for 8-bit code generation. The language includes modern C syntactic features and deliberately omits constructs that require a dynamic runtime stack.

### 2.12.1 Supported Modern C Features

- **From C11:**
  - `static_assert` / `_Static_assert` compile-time assertions.
  - `_Generic` compile-time type matching.
  - Anonymous structures and unions (ISO C11 §6.7.2.1).
- **From C23:**
  - `#embed` and `__has_embed` binary asset embedding.
  - `nullptr` type-safe null pointer keyword.
  - Empty zero-initializers `{}` for structs and arrays.
  - Digit separators in numeric literals (`1_000`, `0b1010_0101`).
  - Typed 8-bit enumerations (`enum : uint8_t`).
  - Single-argument `static_assert(cond)`.
- **From C2Y / C29:**
  - `countof()` compile-time array length operator (WG14 N3169).
  - Named loops (`break label; continue label;` - WG14 N3355).
  - Selection statements with initializers: `if (init; cond)` and `switch (init; expr)`.

### 2.12.2 Features Omitted by Design

- **Floating-point types (`float`, `double`):** Software emulation is prohibitive on 1 MHz 8-bit CPUs. Use `<fixedpoint.h>` instead.
- **Dynamic re-entrant call stack:** KickC uses a static call graph with whole-program SSA register allocation. Recursive functions are not supported.
- **Variable-length arrays (VLAs):** All array memory is statically allocated or allocated to fixed buffers.
- **Runtime multiplication, division, and modulo of variables:** Not generated inline by the 6502 code generator; use the library routines in `<multiply.h>` and `<division.h>`. Calculations on constants and multiplication of a variable by a constant are supported (see Section 2.3.1).
- **Arrays of arrays (`char baa[4][4];`):** Not supported; the compiler reports a `CompileError` (see Section 2.2.6).
- **`alignof` operator.**
- **Variadic functions** (`sum(int i1, ...)`), except `printf`.

### 2.12.3 8-Bit Architecture Extensions

- Low/high byte operators (`<w`, `>w`, `<ptr = val`).
- Forward referencing of variables in the outer scope.
- Interval ranged for-loops: `for (char i : 0..10) { }`.
- Split Low/High word arrays (`__lohi`).
- Struct-of-Arrays flattening (`__soa`).
- Packed 4-bit nibbles (`uint4_t` / `__nibble`).
- 16-bit register pair calling convention (`__ax`).
- Auto Carry-flag boolean returns (`__carry`).
- Countdown loop inversion (`__countdown`).
- Compile-time table generator (`__builtin_lut`).
- Cycle-exact timing delays (`delay_cycles(N)`).
- Memory segment classification and ROM write protection (`#pragma segment_type`).
- Inline 6502 assembly (`asm`) and KickAssembler integration (`kickasm`).

### 2.12.4 Tips for Optimizing KickC Code

1. **Prefer 8-bit `char` / `uint8_t`:** 8-bit operations need fewer cycles, instructions, and registers than 16-bit operations.
2. **Use unsigned types where possible:** Unsigned comparisons and shifts generate shorter assembly sequences than signed two's-complement arithmetic.
3. **Use `__lohi` for large word tables:** Avoids index doubling (`ASL`) and 16-bit pointer math, accessing words in only 8 cycles.
4. **Use `__soa` for struct arrays:** Eliminates multiplication with struct size, enabling direct 4-cycle indexed addressing (`LDA tab,X`).
5. **Use `uint4_t` for nybble buffers:** Halves RAM consumption for Color-RAM buffers and tile attributes.
6. **Use `__countdown` or count down toward zero:** Allows testing `BNE` or `BPL` directly on loop counters without `CPX`/`CPY`.
7. **Use `__carry` for boolean functions:** Returns conditions in the Carry flag and branches directly with `BCS`/`BCC`.
8. **Use `fixed8_8` / `fixed16_8` for physics:** Provides subpixel motion using integer arithmetic.
9. **Adjust `-Ouplift=<N>`:** The option sets how many register combinations are tested per scope (default 100). Lower values compile faster but may produce less optimal register allocation.
10. **Prefer array indexing over incrementing pointers.**
11. **Use `do {} while()` instead of `while() {}`** for loops that always execute at least once.
12. **Booleans are not always efficient;** `char` is often better.
13. **Use `inline` functions** (and experimental inline loops) where call overhead matters.
14. **Store results used multiple times in a variable** instead of repeating the calculation.
15. **Use lookup arrays** instead of repeating expensive calculations.
16. **Unroll loops manually** where code size permits.

# 3 Working with KickC

## 3.1 Controlling Output Files

Compiling with KickC produces several output artifacts:
1. **ASM file (`.asm`):** Human-readable, optimized 6502 assembler code for all C files passed to the compiler and all used library functions.
2. **Binary program (`.prg`, `.crt`, `.bin`, `.d64`):** Produced when using `-a` (assemble) or `-e` (execute in emulator).
3. **Resource files:** Binary graphics, fonts, or music referenced in `#embed` or `kickasm(resource "...")` are automatically copied to the output folder.

The binary program output file name consists of the following components:
- **Output path:** The directory for the output files, determined in this order:
  1. The directory part of the `-o` option, if it contains one.
  2. The `-odir` option.
  3. The directory part of the first C file passed to the compiler.
  4. The current directory.
- **Output basename:** The file name without path and extension: the basename of the `-o` option if given, otherwise the basename of the first C file.
- **Output extension**, determined in this order:
  1. The extension of the `-o` option, if the compiler was instructed to assemble (`-a`, `-e`, or `-d`).
  2. `#pragma extension(...)`, if present in the program.
  3. The `-oext` option.
  4. The extension defined in the `.tgt` file of the selected platform.

The ASM output file is named *output path / output basename . asm extension*. The ASM extension is the extension of the `-o` option if the compiler was not instructed to assemble (`-a`, `-e`, or `-d`), otherwise `asm`.

Resource files needed to assemble the ASM file are copied to the output directory if it differs from the input directory. The linker specification controls the content and format of the binary output and may produce multiple binary files; `%O` expands to the binary output file name.

## 3.2 KickC Command Line Reference

```
kickc [-adeEhSvV] [-Ocoalesce] [-Onocoalesce] [-Oliverangecallpath]
        [-Oloopalign=<threshold>] [-Oloophead] [-Onoloophead] [-Onocache]
        [-Onolongbranchfix] [-Onouplift] [-Ouplift=<N>] [-Si] [-Sl]
        [-struct_model=<model>] [-var_model=<model>] [-vasmoptimize] [-vasmout]
        [-vcreate] [-vfixlongbranch] [-vfragment] [-vliverange] [-vloop]
        [-vnonoptimize] [-voptimize] [-vparse] [-vsequence] [-vsizeinfo]
        [-vunroll] [-vuplift] [-Warraytype] [-Wfragment] [-calling=<calling>]
        [-cpu=<cpu>] [-emu=<emulator>] [-F=<fragmentDir>] [-fragment=<fragment>]
        [-o=<outputFileName>] [-odir=<outputDir>] [-oext=<outputExtension>]
        [-p=<targetPlatform>] [-T=<linkScript>] [-D=<String=String>]...
        [-I=<includeDir>]... [-L=<libDir>]... [-P=<targetPlatformDir>]...
        [-Xassembler=<assemblerOptions>]... [<cFiles>...]
```

### 3.2.1 Description

Compiles C source files into optimized, readable 6502 assembler code and, optionally, assembled binaries. See the [KickC Repository](https://gitlab.com/camelot/kickc) for further information.

### 3.2.2 Parameters

- `<cFiles>...`: One or more C source files to compile.

### 3.2.3 Options

- `-a`: Assemble the generated ASM file with KickAssembler to produce a binary.
- `-calling=<calling>`: Default calling convention (`__phicall`, `__stackcall`, `__varcall`).
- `-cpu=<cpu>`: Target CPU architecture; default is `mos6502x` (6502 with undocumented opcodes) (`mos6502x`, `mos6502`, `rom6502x`, `rom6502`, `wdc65c02`, `csg65ce02`, `mega45gs02`).
- `-d`: Debug assembled binary using C64Debugger.
- `-D=<key=val>`: Define preprocessor macro.
- `-e`: Assemble and immediately execute the binary in the platform emulator (e.g. VICE, Atari800, Nestopia).
- `-E`: Run only the preprocessor and output to stdout.
- `-emu=<emulator>`: Explicitly specify emulator executable.
- `-F, -fragmentdir=<dir>`: Path to ASM fragment directory.
- `-fragment=<name>`: Print ASM implementation or synthesis tree for a specific fragment.
- `-h`, `--help`: Display help and options.
- `-I, -includedir=<dir>`: Add include directory for header files.
- `-L, -libdir=<dir>`: Add library search directory.
- `-o, -output=<file>`: Output filename.
- `-odir=<dir>`: Output directory.
- `-oext=<ext>`: Override binary output extension.
- `-Ocoalesce`: Enable the Zero-Page coalescing pass, which significantly reduces Zero-Page usage but increases compilation time.
- `-Onocoalesce`: Disable all coalescing to reduce compilation time. Zero-Page coalescing (`-Ocoalesce`) is disabled by default.
- `-Oliverangecallpath`: Enable per-call-path variable live-range analysis.
- `-Oloopalign=<N>, -Oloop-align=<N>`: Enables page boundary loop alignment with the given maximum loop size in bytes (e.g. 32). Default is disabled (0).
- `-Oloophead`: Enable experimental loop-head constant pass which identifies loops where the condition is constant on the first iteration.
- `-Onoloophead`: Disable loop-head constant pass.
- `-Onocache`: Disable the fragment synthesis cache (enabled by default).
- `-Onolongbranchfix`: Disable the pass that fixes long branches (relevant when using assemblers with built-in relaxation).
- `-Onouplift`: Disable register uplift allocation (much faster compilation, but significantly slower code).
- `-Ouplift=<N>`: Number of register uplift combinations tested per scope (default 100). Lower values compile faster but may produce less optimal code.
- `-p, -t, -target, -platform=<platform>`: Target platform (default `c64`).
- `-P, -platformdir, -targetdir=<dir>`: Path to platform definition files (`.tgt`).
- `-S, -Sc`: Interleave C source code comments in generated ASM.
- `-Si`: Interleave SSA intermediate language representation in generated ASM.
- `-Sl`: Interleave source filename and line numbers in generated ASM.
- `-struct_model=<unwind|classic>`: Configure struct model (`unwind` or `classic`). Default is `unwind`.
- `-T, -link=<script>`: Use custom linker script.
- `-v`: Verbose output describing compilation phases.
- `-V`, `--version`: Print compiler version and exit.
- `-var_model=<varModel>`: Configure variable optimization/memory area (default `ssa_zp`).
- `-vasmoptimize`: Verbose ASM peephole optimization details.
- `-vasmout`: Show KickAssembler standard output during compilation.
- `-vcreate`: Verbose creation of the SSA control flow graph.
- `-vfixlongbranch`: Verbose logging of fixed long ASM branches and relaxed return trampolines.
- `-vfragment`: Verbose synthesis of Assembler fragments.
- `-vliverange`: Verbose variable live range analysis.
- `-vloop`: Verbose loop analysis.
- `-vnonoptimize`: Verbose logging of choices not to optimize.
- `-voptimize`: Verbose control flow graph optimization.
- `-vparse`: Verbose file parsing.
- `-vsequence`: Verbose sequence plan.
- `-vsizeinfo`: Verbose compiler data structure size information.
- `-vunroll`: Verbose loop unrolling.
- `-vuplift`: Verbose variable register uplift combination optimization.
- `-Warraytype`: Treat non-standard array syntax as a warning instead of an error.
- `-Wfragment`: Treat missing fragments as warnings rather than fatal errors.
- `-Xassembler=<options>`: Pass custom command-line options directly to KickAssembler.

# 4 The Compiler Architecture

The KickC compilation pipeline consists of the following passes:

## 4.1 Loading and Parsing (Pass 0)
Loads source files and recursive `#include` dependencies. The Antlr4 lexer and parser construct the Abstract Syntax Tree (AST), expanding `#embed`, string literal encoding suffixes, and evaluating constant expressions.

## 4.2 SSA Control Flow Graph & Symbol Table Generation (Pass 1)
Translates the AST into a Control Flow Graph (CFG) in Static Single Assignment (SSA) form. Every variable assignment creates a unique SSA version, complex expressions are broken into atomic single-operator statements, and branch convergence points are unified via $\phi$-functions. Verifies ROM segment types and cross-bank calls.

## 4.3 SSA Optimization Micro-Passes (Pass 2)
Applies iterative optimization micro-passes until convergence:
- Constant propagation and dead code elimination.
- Shift-add multiplication factoring.
- Reciprocal constant division and modulo rewriting.
- Loop depth and induction variable analysis.
- Boolean logic minimization.

## 4.4 Live-Range Analysis & Call Graph Resolution (Pass 3)
Builds the whole-program static call graph. Analyzes the live ranges of all SSA variables to compute lifetime intervals and construct the interference graph. Also performs loop analysis (which loops exist, how they nest, and their depth).

## 4.5 Register Allocation & Variable Uplift (Pass 4)
Allocates hardware CPU registers (`A`, `X`, `Y`) and Zero-Page memory addresses to variables:
- **PhiLifting and PhiMemCoalesce:** PhiLifting groups SSA variables that benefit from sharing the same register allocation. PhiMemCoalesce coalesces variables with non-overlapping lifetimes to share Zero-Page memory addresses across disjoint scopes.
- **Register Uplift:** Tests different register combinations for variables and selects the one that produces the assembler code with the fewest cycles. The number of combinations tested is configurable via `-Ouplift=N`.

## 4.6 Assembler Code Generation (Pass 4)
Maps each SSA statement to concrete 6502 assembly using the ASM Fragment Subsystem. Statements with specific register and memory bindings are matched against template fragments from the `fragment` folder of the compiler installation. If the compiler cannot find or synthesize a fragment for an SSA statement, the missing fragment can be added as a file in that folder (see Section 5.2).

## 4.7 Pass 5 Assembler Code Optimization
Applies assembler optimizations by repeatedly running micro-passes until none of them can optimize further:
- **Generic peephole optimizations:** Removal of redundant register loads (e.g. `LDA #0` when `A` is already zero), replacement of jumps to jumps by direct jumps, and removal of unused labels.
- **NMOS-6510 Undocumented Opcodes:** Folds instructions into `LAX`, `SAX`, `ALR`, `DCP`, and `ISC` when targeting `mos6502x`/`rom6502x`.
- **Tail Call Optimization (TCO):** Converts trailing `JSR <proc>; RTS` into direct `JMP <proc>`, and relaxes long-branch return trampolines.
- **Switch Jump Tables:** Replaces linear `CMP`/`BEQ` chains of dense `switch` statements with a split Lo/Hi RTS dispatch table.
- **Pointer-to-SMC in RAM:** Converts Zero-Page pointer dereferences into direct self-modifying code operands in writable RAM.
- **Multi-Entry Byte-Skipping:** Employs `.byte $2c` (`BIT $xxxx`) opcode skipping for shared procedure return paths.
- **Dead Store Elimination with `CanonicalResolver`:** Resolves label aliases and offsets to eliminate redundant stores and unused loads while preserving CPU flags.
- **Page Boundary Loop Alignment:** Inserts conditional `.align $100` padding to prevent inner loops from crossing 256-byte page boundaries.

# 5 Assembler Fragment Subsystem

## 5.1 Fragment Naming Conventions

ASM fragments represent atomic operations with concrete storage allocations. Fragment filenames follow a standard descriptor format:

`[Kind][Width][Sign][Storage]`

- **Kind (first letters):**
  - `v`: Value
  - `p`: Pointer
  - `q`: Pointer to pointer
  - `vbo` / `pbo`: Boolean value / pointer to boolean
  - `vss` / `pss`: Struct value / pointer to struct
- **Width (integer types):**
  - `b`: Byte (8-bit)
  - `w`: Word (16-bit)
  - `d`: Dword (32-bit)
- **Sign:**
  - `u`: Unsigned
  - `s`: Signed
- **Storage (last 2 characters):**
  - `aa`: Accumulator `A`
  - `xx`: Register `X`
  - `yy`: Register `Y`
  - `z1`, `z2`: Zero-Page addresses (`{z1}`, `{z2}`)
  - `m1`, `m2`: Main memory addresses (`{m1}`, `{m2}`)
  - `c1`, `c2`: Constants (`{c1}`, `{c2}`)

Constant interpretation:
- When `c1` is used for a **value** (e.g. `vbuc1`), `{c1}` is an immediate constant `#$xx`.
- When `c1` is used for a **pointer** (e.g. `pbuc1`), `{c1}` represents an absolute address in memory.

Example: `vwuz1_gt_vbsc1_then_la1.asm` implements:
*"If unsigned word at Zero-Page {z1} is greater than signed byte constant {c1}, jump to label {la1}"*.

## 5.2 Fragment Synthesis & Adding Missing Fragments

KickC includes more than 2,000 fragment templates and a synthesis engine. When the compiler encounters an SSA statement without an exact matching fragment file, the synthesizer derives it by combining simpler fragments.

For example, matching an SSA statement like:
```asm
digit#1 = digit#3 + *(UTOA10_VAL#0 + i#2)
```
can be satisfied by synthesizing `vbuz1 = vbuz1_plus_pbuc1_derefidx_vbuxx` from the primitive accumulator addition `vbuaa = vbuaa_plus_vbuz1`:

```
synthesized vbuz1 = vbuz1_plus_pbuc1_derefidx_vbuxx
  < vbuz1 = pbuc1_derefidx_vbuxx_plus_vbuz1
  < vbuaa = pbuc1_derefidx_vbuxx_plus_vbuz1
  < vbuaa = vbuz1_plus_pbuc1_derefidx_vbuxx
  < vbuaa = vbuz1_plus_vbuaa
  < vbuaa = vbuaa_plus_vbuz1  (clobber: A, cycles: 12.5)
```

Generated assembly:
```asm
lda {c1},x
clc
adc {z1}
sta {z1}
```

### Inspecting and Diagnosing Fragments via CLI

To inspect available variations or diagnose how KickC synthesizes a fragment, run the compiler with `-vfragment` and `-fragment`:

```pwsh
kickc.bat -vfragment -fragment "vbuz1=pbuc1_derefidx_vbuz2"
```

The output displays all candidate synthesis paths, cycle costs, and clobbered registers.

### Developer Recipe & Heuristics for Adding Fragments

When encountering an unimplemented or suboptimal fragment, follow these rules of thumb to choose the most general fragment signature:

1. **Register fragments are more general than memory fragments:** A fragment operating on CPU registers (`vbuxx` or `vbuaa`) allows the compiler to synthesize numerous memory-based variants automatically.
2. **Main-memory fragments are more general than Zero-Page fragments:** KickC can lower main-memory fragments (`vbum1`) to Zero-Page (`vbuz1`), but cannot do the reverse for indirect addressing modes.
3. **Keep fragments compact:** Shorter, focused fragments allow the synthesizer to reuse them across varied expression trees.

**Example.** Assume the fragment `vbuz1=pbuz2_derefidx_vbuc1_minus_vbuxx` is missing. Running

```pwsh
kickc.bat -vfragment -fragment vbuz1=pbuz2_derefidx_vbuc1_minus_vbuxx
```

lists all fragments the compiler could use to synthesize the missing one (e.g. `vbuz1=pbuz2_derefidx_vbuc1_minus_vbuaa`, `..._vbuyy`). Of these, choose the most general fragment for which optimal assembler can be written, i.e. the one that allows the most other fragments to be synthesized. Here, `vbuaa=pbuz1_derefidx_vbuyy_minus_vbuaa.asm` is a good choice:

```asm
eor #$ff
sec
adc ({z1}),y
```

After adding the file, run the command again to verify that the missing fragment is now synthesized and that the resulting assembler is acceptable:

```
synthesized vbuz1=pbuz2_derefidx_vbuc1_minus_vbuxx
  < vbum1=pbuz2_derefidx_vbuc1_minus_vbuxx
  < vbuaa=pbuz1_derefidx_vbuc1_minus_vbuxx
  < vbuaa=pbuz1_derefidx_vbuyy_minus_vbuxx
  < vbuaa=pbuz1_derefidx_vbuyy_minus_vbuaa  (clobber: A Y, cycles: 16.5)
  ldy #{c1}
  txa
  eor #$ff
  sec
  adc ({z2}),y
  sta {z1}
```

### Authoring Custom Fragments in `fragment/mos6502-common/`

- **Scratchpad Zero-Page:** The Zero-Page addresses **`$fe` and `$ff`** are reserved for fragments requiring temporary indirect pointers.
- **Clobber Tracking:** Never manually save or restore registers inside fragments (`pha`/`pla`). The compiler tracks the clobbered registers (see Section 5.3).
- **Cache Invalidation:** The fragment cache (`fragment/cache/`) automatically invalidates when files in the fragment directory are updated.


## 5.3 Handling Clobbered Registers

Fragments do not need to restore register values. The compiler analyzes the assembler code of each fragment and determines which registers it clobbers. When allocating variables to registers, it avoids allocations where a fragment clobbers a register that holds a value needed later. A value needed after a fragment that clobbers `A` is therefore held in `X`, `Y`, or on Zero-Page instead.

This produces better assembler code than having each fragment restore registers, because the compiler can choose the register/Zero-Page allocation that minimizes the number of cycles. Since each assignment to a variable is treated as a separate SSA variable, frequently used variables may be held in different registers or Zero-Page locations in different parts of the code.
