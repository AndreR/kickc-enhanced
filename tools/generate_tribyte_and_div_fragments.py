#!/usr/bin/env python3
"""
Tool: generate_tribyte_and_div_fragments.py
Generates the orthogonal matrix of 24-bit Tribyte (vtu / vts) and 16-bit division-by-10 ASM fragments for KickC.
Idempotent and synchronized across kickc-src and KickC runtime.
"""

import os
import sys

def get_dirs():
    return [
        r"C:\C64\KickC\fragment\mos6502-common",
        r"c:\C64\kickc-src\src\main\fragment\mos6502-common"
    ]

def write_fragment(name, asm, dirs):
    clean = asm.strip() + "\n"
    created = 0
    updated = 0
    unchanged = 0
    for d in dirs:
        if not os.path.exists(d):
            os.makedirs(d, exist_ok=True)
        fp = os.path.join(d, name)
        if not os.path.exists(fp):
            with open(fp, "w", encoding="utf-8", newline="\n") as f:
                f.write(clean)
            created += 1
        else:
            with open(fp, "r", encoding="utf-8", errors="ignore") as f:
                cur = f.read()
            if cur != clean:
                with open(fp, "w", encoding="utf-8", newline="\n") as f:
                    f.write(clean)
                updated += 1
            else:
                unchanged += 1
    return created, updated, unchanged

def main():
    dirs = get_dirs()
    print("=== Generating Orthogonal 24-Bit Tribyte and Div10 ASM Fragments ===\n")
    frags = {}

    locs = [('z', 'z'), ('z', 'm'), ('m', 'z'), ('m', 'm')]

    # =========================================================================
    # Part 1: 16-bit Division by 10 (Compact ~23-byte 6502 shift-subtract)
    # =========================================================================
    # 1.1 In-place: vwuz1=vwuz1_div_10
    frags["vwuz1=vwuz1_div_10.asm"] = (
        "lda #0\n"
        "ldx #16\n"
        "clc\n"
        "!:\n"
        "rol {z1}\n"
        "rol {z1}+1\n"
        "rol\n"
        "cmp #10\n"
        "bcc !+\n"
        "sbc #10\n"
        "!:\n"
        "dex\n"
        "bne !--\n"
        "rol {z1}\n"
        "rol {z1}+1"
    )

    # 1.2 In-place memory: vwum1=vwum1_div_10
    frags["vwum1=vwum1_div_10.asm"] = (
        "lda #0\n"
        "ldx #16\n"
        "clc\n"
        "!:\n"
        "rol {m1}\n"
        "rol {m1}+1\n"
        "rol\n"
        "cmp #10\n"
        "bcc !+\n"
        "sbc #10\n"
        "!:\n"
        "dex\n"
        "bne !--\n"
        "rol {m1}\n"
        "rol {m1}+1"
    )

    # 1.3 Out-of-place: vwuz1=vwuz2_div_10
    frags["vwuz1=vwuz2_div_10.asm"] = (
        "lda {z2}\n"
        "sta {z1}\n"
        "lda {z2}+1\n"
        "sta {z1}+1\n"
        "lda #0\n"
        "ldx #16\n"
        "clc\n"
        "!:\n"
        "rol {z1}\n"
        "rol {z1}+1\n"
        "rol\n"
        "cmp #10\n"
        "bcc !+\n"
        "sbc #10\n"
        "!:\n"
        "dex\n"
        "bne !--\n"
        "rol {z1}\n"
        "rol {z1}+1"
    )

    # 1.4 Out-of-place: vwuz1=vwum2_div_10
    frags["vwuz1=vwum2_div_10.asm"] = (
        "lda {m2}\n"
        "sta {z1}\n"
        "lda {m2}+1\n"
        "sta {z1}+1\n"
        "lda #0\n"
        "ldx #16\n"
        "clc\n"
        "!:\n"
        "rol {z1}\n"
        "rol {z1}+1\n"
        "rol\n"
        "cmp #10\n"
        "bcc !+\n"
        "sbc #10\n"
        "!:\n"
        "dex\n"
        "bne !--\n"
        "rol {z1}\n"
        "rol {z1}+1"
    )

    # 1.5 Out-of-place: vwum1=vwuz2_div_10
    frags["vwum1=vwuz2_div_10.asm"] = (
        "lda {z2}\n"
        "sta {m1}\n"
        "lda {z2}+1\n"
        "sta {m1}+1\n"
        "lda #0\n"
        "ldx #16\n"
        "clc\n"
        "!:\n"
        "rol {m1}\n"
        "rol {m1}+1\n"
        "rol\n"
        "cmp #10\n"
        "bcc !+\n"
        "sbc #10\n"
        "!:\n"
        "dex\n"
        "bne !--\n"
        "rol {m1}\n"
        "rol {m1}+1"
    )

    # 1.6 Out-of-place: vwum1=vwum2_div_10
    frags["vwum1=vwum2_div_10.asm"] = (
        "lda {m2}\n"
        "sta {m1}\n"
        "lda {m2}+1\n"
        "sta {m1}+1\n"
        "lda #0\n"
        "ldx #16\n"
        "clc\n"
        "!:\n"
        "rol {m1}\n"
        "rol {m1}+1\n"
        "rol\n"
        "cmp #10\n"
        "bcc !+\n"
        "sbc #10\n"
        "!:\n"
        "dex\n"
        "bne !--\n"
        "rol {m1}\n"
        "rol {m1}+1"
    )


    # 1.7 Generic 16-bit division by 8-bit constant (c1)
    frags["vwuz1=vwuz1_div_vbuc1.asm"] = (
        "lda #0\n"
        "ldx #16\n"
        "clc\n"
        "!:\n"
        "rol {z1}\n"
        "rol {z1}+1\n"
        "rol\n"
        "cmp #{c1}\n"
        "bcc !+\n"
        "sbc #{c1}\n"
        "!:\n"
        "dex\n"
        "bne !--\n"
        "rol {z1}\n"
        "rol {z1}+1"
    )

    frags["vwum1=vwum1_div_vbuc1.asm"] = (
        "lda #0\n"
        "ldx #16\n"
        "clc\n"
        "!:\n"
        "rol {m1}\n"
        "rol {m1}+1\n"
        "rol\n"
        "cmp #{c1}\n"
        "bcc !+\n"
        "sbc #{c1}\n"
        "!:\n"
        "dex\n"
        "bne !--\n"
        "rol {m1}\n"
        "rol {m1}+1"
    )

    frags["vwuz1=vwuz2_div_vbuc1.asm"] = (
        "lda {z2}\n"
        "sta {z1}\n"
        "lda {z2}+1\n"
        "sta {z1}+1\n"
        "lda #0\n"
        "ldx #16\n"
        "clc\n"
        "!:\n"
        "rol {z1}\n"
        "rol {z1}+1\n"
        "rol\n"
        "cmp #{c1}\n"
        "bcc !+\n"
        "sbc #{c1}\n"
        "!:\n"
        "dex\n"
        "bne !--\n"
        "rol {z1}\n"
        "rol {z1}+1"
    )

    frags["vwuz1=vwum2_div_vbuc1.asm"] = (
        "lda {m2}\n"
        "sta {z1}\n"
        "lda {m2}+1\n"
        "sta {z1}+1\n"
        "lda #0\n"
        "ldx #16\n"
        "clc\n"
        "!:\n"
        "rol {z1}\n"
        "rol {z1}+1\n"
        "rol\n"
        "cmp #{c1}\n"
        "bcc !+\n"
        "sbc #{c1}\n"
        "!:\n"
        "dex\n"
        "bne !--\n"
        "rol {z1}\n"
        "rol {z1}+1"
    )

    frags["vwum1=vwuz2_div_vbuc1.asm"] = (
        "lda {z2}\n"
        "sta {m1}\n"
        "lda {z2}+1\n"
        "sta {m1}+1\n"
        "lda #0\n"
        "ldx #16\n"
        "clc\n"
        "!:\n"
        "rol {m1}\n"
        "rol {m1}+1\n"
        "rol\n"
        "cmp #{c1}\n"
        "bcc !+\n"
        "sbc #{c1}\n"
        "!:\n"
        "dex\n"
        "bne !--\n"
        "rol {m1}\n"
        "rol {m1}+1"
    )

    frags["vwum1=vwum2_div_vbuc1.asm"] = (
        "lda {m2}\n"
        "sta {m1}\n"
        "lda {m2}+1\n"
        "sta {m1}+1\n"
        "lda #0\n"
        "ldx #16\n"
        "clc\n"
        "!:\n"
        "rol {m1}\n"
        "rol {m1}+1\n"
        "rol\n"
        "cmp #{c1}\n"
        "bcc !+\n"
        "sbc #{c1}\n"
        "!:\n"
        "dex\n"
        "bne !--\n"
        "rol {m1}\n"
        "rol {m1}+1"
    )

    # =========================================================================
    # Part 2: 24-bit Tribyte Fragments (vtu / vts)
    # =========================================================================
    types = ['vtu', 'vts']

    # 2.1 Copy: vtu1 = vtu2
    for t in types:
        for d_loc, s_loc in locs:
            d = f"{t}{d_loc}1"
            s = f"{t}{s_loc}2"
            d_val = f"{{{d_loc}1}}"
            s_val = f"{{{s_loc}2}}"
            frags[f"{d}={s}.asm"] = (
                f"lda {s_val}\nsta {d_val}\n"
                f"lda {s_val}+1\nsta {d_val}+1\n"
                f"lda {s_val}+2\nsta {d_val}+2"
            )

    # 2.2 Constant assignment: vtu1 = vtuc1 / vts1 = vtsc1
    for t in types:
        c_name = "vtuc1" if t == "vtu" else "vtsc1"
        for loc in ['z', 'm']:
            d = f"{t}{loc}1"
            d_val = f"{{{loc}1}}"
            frags[f"{d}={c_name}.asm"] = (
                f"lda #<{{c1}}\nsta {d_val}\n"
                f"lda #>{{c1}}\nsta {d_val}+1\n"
                f"lda #<{{c1}}>>$10\nsta {d_val}+2"
            )
            # Plus constant
            frags[f"{d}={d}_plus_{c_name}.asm"] = (
                f"clc\n"
                f"lda {d_val}\nadc #<{{c1}}\nsta {d_val}\n"
                f"lda {d_val}+1\nadc #>{{c1}}\nsta {d_val}+1\n"
                f"lda {d_val}+2\nadc #<{{c1}}>>$10\nsta {d_val}+2"
            )
            # Minus constant
            frags[f"{d}={d}_minus_{c_name}.asm"] = (
                f"sec\n"
                f"lda {d_val}\nsbc #<{{c1}}\nsta {d_val}\n"
                f"lda {d_val}+1\nsbc #>{{c1}}\nsta {d_val}+1\n"
                f"lda {d_val}+2\nsbc #<{{c1}}>>$10\nsta {d_val}+2"
            )

    # 2.3 Addition: vtu1 = vtu2 + vtu3
    for d_loc, s1_loc in locs:
        for s2_loc in ['z', 'm']:
            d = f"vtu{d_loc}1"
            s1 = f"vtu{s1_loc}2"
            s2 = f"vtu{s2_loc}3"
            d_val = f"{{{d_loc}1}}"
            s1_val = f"{{{s1_loc}2}}"
            s2_val = f"{{{s2_loc}3}}"
            frags[f"{d}={s1}_plus_{s2}.asm"] = (
                f"clc\n"
                f"lda {s1_val}\nadc {s2_val}\nsta {d_val}\n"
                f"lda {s1_val}+1\nadc {s2_val}+1\nsta {d_val}+1\n"
                f"lda {s1_val}+2\nadc {s2_val}+2\nsta {d_val}+2"
            )

    # 2.4 Subtraction: vtu1 = vtu2 - vtu3
    for d_loc, s1_loc in locs:
        for s2_loc in ['z', 'm']:
            d = f"vtu{d_loc}1"
            s1 = f"vtu{s1_loc}2"
            s2 = f"vtu{s2_loc}3"
            d_val = f"{{{d_loc}1}}"
            s1_val = f"{{{s1_loc}2}}"
            s2_val = f"{{{s2_loc}3}}"
            frags[f"{d}={s1}_minus_{s2}.asm"] = (
                f"sec\n"
                f"lda {s1_val}\nsbc {s2_val}\nsta {d_val}\n"
                f"lda {s1_val}+1\nsbc {s2_val}+1\nsta {d_val}+1\n"
                f"lda {s1_val}+2\nsbc {s2_val}+2\nsta {d_val}+2"
            )

    # 2.5 Zero-extend 8-bit to 24-bit: vtu1 = vbu2
    for d_loc, s_loc in locs:
        d = f"vtu{d_loc}1"
        s = f"vbu{s_loc}2"
        d_val = f"{{{d_loc}1}}"
        s_val = f"{{{s_loc}2}}"
        frags[f"{d}={s}.asm"] = (
            f"lda {s_val}\nsta {d_val}\n"
            f"lda #0\nsta {d_val}+1\nsta {d_val}+2"
        )
        frags[f"{d}=_tribyte_{s}.asm"] = frags[f"{d}={s}.asm"]

    # 2.6 Zero-extend 16-bit to 24-bit: vtu1 = vwu2
    for d_loc, s_loc in locs:
        d = f"vtu{d_loc}1"
        s = f"vwu{s_loc}2"
        d_val = f"{{{d_loc}1}}"
        s_val = f"{{{s_loc}2}}"
        frags[f"{d}={s}.asm"] = (
            f"lda {s_val}\nsta {d_val}\n"
            f"lda {s_val}+1\nsta {d_val}+1\n"
            f"lda #0\nsta {d_val}+2"
        )
        frags[f"{d}=_tribyte_{s}.asm"] = frags[f"{d}={s}.asm"]

    # 2.7 Truncate 24-bit to 16-bit: vwu1 = vtu2
    for d_loc, s_loc in locs:
        d = f"vwu{d_loc}1"
        s = f"vtu{s_loc}2"
        d_val = f"{{{d_loc}1}}"
        s_val = f"{{{s_loc}2}}"
        frags[f"{d}={s}.asm"] = (
            f"lda {s_val}\nsta {d_val}\n"
            f"lda {s_val}+1\nsta {d_val}+1"
        )
        frags[f"{d}=_word_{s}.asm"] = frags[f"{d}={s}.asm"]

    # 2.8 Truncate 24-bit to 8-bit: vbu1 = vtu2
    for d_loc, s_loc in locs:
        d = f"vbu{d_loc}1"
        s = f"vtu{s_loc}2"
        d_val = f"{{{d_loc}1}}"
        s_val = f"{{{s_loc}2}}"
        frags[f"{d}={s}.asm"] = (
            f"lda {s_val}\nsta {d_val}"
        )
        frags[f"{d}=_byte_{s}.asm"] = frags[f"{d}={s}.asm"]

    # 2.9 Zero-extend 24-bit to 32-bit: vdu1 = vtu2
    for d_loc, s_loc in locs:
        d = f"vdu{d_loc}1"
        s = f"vtu{s_loc}2"
        d_val = f"{{{d_loc}1}}"
        s_val = f"{{{s_loc}2}}"
        frags[f"{d}={s}.asm"] = (
            f"lda {s_val}\nsta {d_val}\n"
            f"lda {s_val}+1\nsta {d_val}+1\n"
            f"lda {s_val}+2\nsta {d_val}+2\n"
            f"lda #0\nsta {d_val}+3"
        )
        frags[f"{d}=_dword_{s}.asm"] = frags[f"{d}={s}.asm"]

    # 2.10 Truncate 32-bit to 24-bit: vtu1 = vdu2
    for d_loc, s_loc in locs:
        d = f"vtu{d_loc}1"
        s = f"vdu{s_loc}2"
        d_val = f"{{{d_loc}1}}"
        s_val = f"{{{s_loc}2}}"
        frags[f"{d}={s}.asm"] = (
            f"lda {s_val}\nsta {d_val}\n"
            f"lda {s_val}+1\nsta {d_val}+1\n"
            f"lda {s_val}+2\nsta {d_val}+2"
        )
        frags[f"{d}=_tribyte_{s}.asm"] = frags[f"{d}={s}.asm"]

    # 2.11 Bitwise AND, OR, XOR
    ops = [('band', 'and'), ('bor', 'ora'), ('bxor', 'eor')]
    for op_name, op_asm in ops:
        for d_loc, s1_loc in locs:
            for s2_loc in ['z', 'm']:
                d = f"vtu{d_loc}1"
                s1 = f"vtu{s1_loc}2"
                s2 = f"vtu{s2_loc}3"
                d_val = f"{{{d_loc}1}}"
                s1_val = f"{{{s1_loc}2}}"
                s2_val = f"{{{s2_loc}3}}"
                frags[f"{d}={s1}_{op_name}_{s2}.asm"] = (
                    f"lda {s1_val}\n{op_asm} {s2_val}\nsta {d_val}\n"
                    f"lda {s1_val}+1\n{op_asm} {s2_val}+1\nsta {d_val}+1\n"
                    f"lda {s1_val}+2\n{op_asm} {s2_val}+2\nsta {d_val}+2"
                )

    # 2.12 Bitwise NOT: vtu1 = bnot_vtu2
    for d_loc, s_loc in locs:
        d = f"vtu{d_loc}1"
        s = f"vtu{s_loc}2"
        d_val = f"{{{d_loc}1}}"
        s_val = f"{{{s_loc}2}}"
        frags[f"{d}=bnot_{s}.asm"] = (
            f"lda {s_val}\neor #$ff\nsta {d_val}\n"
            f"lda {s_val}+1\neor #$ff\nsta {d_val}+1\n"
            f"lda {s_val}+2\neor #$ff\nsta {d_val}+2"
        )

    # 2.13 Compare Equality: vtu1 == vtu2 then la1
    for s1_loc, s2_loc in locs:
        s1 = f"vtu{s1_loc}1"
        s2 = f"vtu{s2_loc}2"
        s1_val = f"{{{s1_loc}1}}"
        s2_val = f"{{{s2_loc}2}}"
        frags[f"{s1}_eq_{s2}_then_la1.asm"] = (
            f"lda {s1_val}\ncmp {s2_val}\nbne !+\n"
            f"lda {s1_val}+1\ncmp {s2_val}+1\nbne !+\n"
            f"lda {s1_val}+2\ncmp {s2_val}+2\nbeq {{la1}}\n"
            f"!:"
        )
        frags[f"{s1}_neq_{s2}_then_la1.asm"] = (
            f"lda {s1_val}\ncmp {s2_val}\nbne {{la1}}\n"
            f"lda {s1_val}+1\ncmp {s2_val}+1\nbne {{la1}}\n"
            f"lda {s1_val}+2\ncmp {s2_val}+2\nbne {{la1}}"
        )

    # 2.14 Compare Less Than / Greater Than: vtu1 < vtu2 then la1
    for s1_loc, s2_loc in locs:
        s1 = f"vtu{s1_loc}1"
        s2 = f"vtu{s2_loc}2"
        s1_val = f"{{{s1_loc}1}}"
        s2_val = f"{{{s2_loc}2}}"
        frags[f"{s1}_lt_{s2}_then_la1.asm"] = (
            f"lda {s1_val}+2\ncmp {s2_val}+2\nbcc {{la1}}\nbne !+\n"
            f"lda {s1_val}+1\ncmp {s2_val}+1\nbcc {{la1}}\nbne !+\n"
            f"lda {s1_val}\ncmp {s2_val}\nbcc {{la1}}\n"
            f"!:"
        )
        frags[f"{s1}_ge_{s2}_then_la1.asm"] = (
            f"lda {s1_val}+2\ncmp {s2_val}+2\nbcc !+\nbne {{la1}}\n"
            f"lda {s1_val}+1\ncmp {s2_val}+1\nbcc !+\nbne {{la1}}\n"
            f"lda {s1_val}\ncmp {s2_val}\nbcs {{la1}}\n"
            f"!:"
        )

    # 2.15 Shifts: rol 1, ror 1, ror 8, ror 16
    for loc in ['z', 'm']:
        d = f"vtu{loc}1"
        val = f"{{{loc}1}}"
        frags[f"{d}={d}_rol_1.asm"] = (
            f"asl {val}\nrol {val}+1\nrol {val}+2"
        )
        frags[f"{d}={d}_ror_1.asm"] = (
            f"lsr {val}+2\nror {val}+1\nror {val}"
        )
        frags[f"{d}={d}_ror_8.asm"] = (
            f"lda {val}+1\nsta {val}\n"
            f"lda {val}+2\nsta {val}+1\n"
            f"lda #0\nsta {val}+2"
        )
        frags[f"{d}={d}_ror_16.asm"] = (
            f"lda {val}+2\nsta {val}\n"
            f"lda #0\nsta {val}+1\nsta {val}+2"
        )


    # 2.16 Dereference Stores & Loads
    # _deref_ptuc1 = vtu2
    frags['_deref_ptuc1=vtuz2.asm'] = 'lda {z2}\nsta {c1}\nlda {z2}+1\nsta {c1}+1\nlda {z2}+2\nsta {c1}+2'
    frags['_deref_ptuc1=vtum2.asm'] = 'lda {m2}\nsta {c1}\nlda {m2}+1\nsta {c1}+1\nlda {m2}+2\nsta {c1}+2'
    frags['_deref_ptuc1=vtuc2.asm'] = 'lda #<{c2}\nsta {c1}\nlda #>{c2}\nsta {c1}+1\nlda #<{c2}>>$10\nsta {c1}+2'
    frags['_deref_ptsc1=vtsz2.asm'] = frags['_deref_ptuc1=vtuz2.asm']
    frags['_deref_ptsc1=vtsm2.asm'] = frags['_deref_ptuc1=vtum2.asm']
    frags['_deref_ptsc1=vtsc2.asm'] = frags['_deref_ptuc1=vtuc2.asm']

    # _deref_ptuz1 = vtu2
    frags['_deref_ptuz1=vtuz2.asm'] = 'ldy #0\nlda {z2}\nsta ({z1}),y\niny\nlda {z2}+1\nsta ({z1}),y\niny\nlda {z2}+2\nsta ({z1}),y'
    frags['_deref_ptuz1=vtum2.asm'] = 'ldy #0\nlda {m2}\nsta ({z1}),y\niny\nlda {m2}+1\nsta ({z1}),y\niny\nlda {m2}+2\nsta ({z1}),y'
    frags['_deref_ptum1=vtuz2.asm'] = 'lda {m1}\nsta \nlda {m1}+1\nsta \nldy #0\nlda {z2}\nsta (),y\niny\nlda {z2}+1\nsta (),y\niny\nlda {z2}+2\nsta (),y'
    frags['_deref_ptum1=vtum2.asm'] = 'lda {m1}\nsta \nlda {m1}+1\nsta \nldy #0\nlda {m2}\nsta (),y\niny\nlda {m2}+1\nsta (),y\niny\nlda {m2}+2\nsta (),y'

    # vtu1 = _deref_ptuc1
    frags['vtuz1=_deref_ptuc1.asm'] = 'lda {c1}\nsta {z1}\nlda {c1}+1\nsta {z1}+1\nlda {c1}+2\nsta {z1}+2'
    frags['vtum1=_deref_ptuc1.asm'] = 'lda {c1}\nsta {m1}\nlda {c1}+1\nsta {m1}+1\nlda {c1}+2\nsta {m1}+2'
    frags['vtsz1=_deref_ptsc1.asm'] = frags['vtuz1=_deref_ptuc1.asm']
    frags['vtsm1=_deref_ptsc1.asm'] = frags['vtum1=_deref_ptuc1.asm']

    # vtu1 = _deref_ptuz2
    frags['vtuz1=_deref_ptuz2.asm'] = 'ldy #0\nlda ({z2}),y\nsta {z1}\niny\nlda ({z2}),y\nsta {z1}+1\niny\nlda ({z2}),y\nsta {z1}+2'
    frags['vtum1=_deref_ptuz2.asm'] = 'ldy #0\nlda ({z2}),y\nsta {m1}\niny\nlda ({z2}),y\nsta {m1}+1\niny\nlda ({z2}),y\nsta {m1}+2'
    frags['vtuz1=_deref_ptum2.asm'] = 'lda {m2}\nsta \nlda {m2}+1\nsta \nldy #0\nlda (),y\nsta {z1}\niny\nlda (),y\nsta {z1}+1\niny\nlda (),y\nsta {z1}+2'
    frags['vtum1=_deref_ptum2.asm'] = 'lda {m2}\nsta \nlda {m2}+1\nsta \nldy #0\nlda (),y\nsta {m1}\niny\nlda (),y\nsta {m1}+1\niny\nlda (),y\nsta {m1}+2'

    # 2.17 Orthogonal ptuc1_derefidx loads and stores (for 24-bit Tribyte and 32-bit Dword)
    idx_types = [
        ('vbuz', 'ldy {z_idx}\n'),
        ('vbum', 'ldy {m_idx}\n'),
        ('vbuyy', ''),
        ('vbuxx', 'txa\ntay\n'),
        ('vbuc', 'ldy #{c_idx}\n'),
    ]

    val_types = [
        ('vtuz', 3, 'z'),
        ('vtum', 3, 'm'),
        ('vduz', 4, 'z'),
        ('vdum', 4, 'm'),
        ('vtsz', 3, 'z'),
        ('vtsm', 3, 'm'),
    ]

    for val_prefix, val_bytes, val_loc in val_types:
        for idx_prefix, idx_setup in idx_types:
            # Store: ptuc1_derefidx_{idx} = {val}
            sig_store = f"ptuc1_derefidx_{idx_prefix}1={val_prefix}2" if idx_prefix in ['vbuz', 'vbum'] else f"ptuc1_derefidx_{idx_prefix}={val_prefix}1"
            val_idx = "2" if idx_prefix in ['vbuz', 'vbum'] else "1"
            idx_name = f"{val_loc}{val_idx}"
            
            store_lines = []
            if idx_setup:
                setup_line = idx_setup.replace("{z_idx}", "{z1}").replace("{m_idx}", "{m1}").replace("{c_idx}", "{c1}")
                store_lines.append(setup_line.strip())
            
            # Store 3 bytes into ptuc1
            for b in range(3):
                offset_c = f"+{b}" if b > 0 else ""
                offset_v = f"+{b}" if b > 0 else ""
                store_lines.append(f"lda {{{val_loc}{val_idx}}}{offset_v}")
                store_lines.append(f"sta {{c1}}{offset_c},y")
            
            frags[f"{sig_store}.asm"] = "\n".join(store_lines)

            # Load: {val} = ptuc1_derefidx_{idx}
            sig_load = f"{val_prefix}1=ptuc1_derefidx_{idx_prefix}2" if idx_prefix in ['vbuz', 'vbum'] else f"{val_prefix}1=ptuc1_derefidx_{idx_prefix}"
            idx_var = "{z2}" if idx_prefix == 'vbuz' else ("{m2}" if idx_prefix == 'vbum' else "{c2}")
            load_lines = []
            if idx_setup:
                setup_line = idx_setup.replace("{z_idx}", idx_var).replace("{m_idx}", idx_var).replace("{c_idx}", idx_var)
                load_lines.append(setup_line.strip())
            
            for b in range(3):
                offset_c = f"+{b}" if b > 0 else ""
                offset_v = f"+{b}" if b > 0 else ""
                load_lines.append(f"lda {{c1}}{offset_c},y")
                load_lines.append(f"sta {{{val_loc}1}}{offset_v}")
            if val_bytes == 4:
                load_lines.append("lda #0")
                load_lines.append(f"sta {{{val_loc}1}}+3")
            
            frags[f"{sig_load}.asm"] = "\n".join(load_lines)


    # 2.18 Constant Stores into ptuc1_derefidx
    for idx_prefix, idx_setup in idx_types:
        idx_sig = f"{idx_prefix}1" if idx_prefix in ['vbuz', 'vbum'] else idx_prefix
        val_sig = "{c2}"
        
        # Byte constant
        sig_vbuc = f"ptuc1_derefidx_{idx_sig}=vbuc2"
        lines_vbuc = []
        if idx_setup:
            lines_vbuc.append(idx_setup.replace("{z_idx}", "{z1}").replace("{m_idx}", "{m1}").replace("{c_idx}", "{c1}").strip())
        lines_vbuc.append("lda #{{c2}}")
        lines_vbuc.append("sta {c1},y")
        lines_vbuc.append("lda #0")
        lines_vbuc.append("sta {c1}+1,y")
        lines_vbuc.append("sta {c1}+2,y")
        frags[f"{sig_vbuc}.asm"] = "\n".join(lines_vbuc)

        # Word constant
        sig_vwuc = f"ptuc1_derefidx_{idx_sig}=vwuc2"
        lines_vwuc = []
        if idx_setup:
            lines_vwuc.append(idx_setup.replace("{z_idx}", "{z1}").replace("{m_idx}", "{m1}").replace("{c_idx}", "{c1}").strip())
        lines_vwuc.append("lda #<{c2}")
        lines_vwuc.append("sta {c1},y")
        lines_vwuc.append("lda #>{c2}")
        lines_vwuc.append("sta {c1}+1,y")
        lines_vwuc.append("lda #0")
        lines_vwuc.append("sta {c1}+2,y")
        frags[f"{sig_vwuc}.asm"] = "\n".join(lines_vwuc)

        # Tribyte constant
        sig_vtuc = f"ptuc1_derefidx_{idx_sig}=vtuc2"
        lines_vtuc = []
        if idx_setup:
            lines_vtuc.append(idx_setup.replace("{z_idx}", "{z1}").replace("{m_idx}", "{m1}").replace("{c_idx}", "{c1}").strip())
        lines_vtuc.append("lda #<{c2}")
        lines_vtuc.append("sta {c1},y")
        lines_vtuc.append("lda #>{c2}")
        lines_vtuc.append("sta {c1}+1,y")
        lines_vtuc.append("lda #<{c2}>>$10")
        lines_vtuc.append("sta {c1}+2,y")
        frags[f"{sig_vtuc}.asm"] = "\n".join(lines_vtuc)

        # Dword constant
        sig_vduc = f"ptuc1_derefidx_{idx_sig}=vduc2"
        frags[f"{sig_vduc}.asm"] = frags[f"{sig_vtuc}.asm"]

        # Signed variants
        frags[f"ptsc1_derefidx_{idx_sig}=vbsc2.asm"] = frags[f"{sig_vbuc}.asm"]
        frags[f"ptsc1_derefidx_{idx_sig}=vwsc2.asm"] = frags[f"{sig_vwuc}.asm"]
        frags[f"ptsc1_derefidx_{idx_sig}=vtsc2.asm"] = frags[f"{sig_vtuc}.asm"]
        frags[f"ptsc1_derefidx_{idx_sig}=vdsc2.asm"] = frags[f"{sig_vduc}.asm"]


    # 2.19 Mixed-Width Tribyte Comparisons (vtu vs vwu, vtu vs vdu, vtu vs vbu, and constants)
    locs2 = [('z', 'z'), ('z', 'm'), ('m', 'z'), ('m', 'm')]

    # 1. vtu vs vwu (24-bit vs 16-bit)
    for l1, l2 in locs2:
        s1 = f"vtu{l1}1"
        s2 = f"vwu{l2}2"
        v1 = f"{{{l1}1}}"
        v2 = f"{{{l2}2}}"

        # GE (>=)
        frags[f"{s1}_ge_{s2}_then_la1.asm"] = (
            f"lda {v1}+2\nbne {{la1}}\n"
            f"lda {v1}+1\ncmp {v2}+1\nbcc !+\nbne {{la1}}\n"
            f"lda {v1}\ncmp {v2}\nbcs {{la1}}\n"
            f"!:"
        )
        # GT (>)
        frags[f"{s1}_gt_{s2}_then_la1.asm"] = (
            f"lda {v1}+2\nbne {{la1}}\n"
            f"lda {v1}+1\ncmp {v2}+1\nbcc !+\nbne {{la1}}\n"
            f"lda {v1}\ncmp {v2}\nbeq !+\nbcs {{la1}}\n"
            f"!:"
        )
        # LT (<)
        frags[f"{s1}_lt_{s2}_then_la1.asm"] = (
            f"lda {v1}+2\nbne !+\n"
            f"lda {v1}+1\ncmp {v2}+1\nbcc {{la1}}\nbne !+\n"
            f"lda {v1}\ncmp {v2}\nbcc {{la1}}\n"
            f"!:"
        )
        # LE (<=)
        frags[f"{s1}_le_{s2}_then_la1.asm"] = (
            f"lda {v1}+2\nbne !+\n"
            f"lda {v1}+1\ncmp {v2}+1\nbcc {{la1}}\nbne !+\n"
            f"lda {v1}\ncmp {v2}\nbcc {{la1}}\nbeq {{la1}}\n"
            f"!:"
        )
        # EQ (==)
        frags[f"{s1}_eq_{s2}_then_la1.asm"] = (
            f"lda {v1}+2\nbne !+\n"
            f"lda {v1}+1\ncmp {v2}+1\nbne !+\n"
            f"lda {v1}\ncmp {v2}\nbeq {{la1}}\n"
            f"!:"
        )
        # NEQ (!=)
        frags[f"{s1}_neq_{s2}_then_la1.asm"] = (
            f"lda {v1}+2\nbne {{la1}}\n"
            f"lda {v1}+1\ncmp {v2}+1\nbne {{la1}}\n"
            f"lda {v1}\ncmp {v2}\nbne {{la1}}"
        )

        # Reverse: vwu vs vtu
        r1 = f"vwu{l1}1"
        r2 = f"vtu{l2}2"
        rv1 = f"{{{l1}1}}"
        rv2 = f"{{{l2}2}}"

        # vwu >= vtu <=> vtu <= vwu
        frags[f"{r1}_ge_{r2}_then_la1.asm"] = (
            f"lda {rv2}+2\nbne !+\n"
            f"lda {rv1}+1\ncmp {rv2}+1\nbcc !+\nbne {{la1}}\n"
            f"lda {rv1}\ncmp {rv2}\nbcs {{la1}}\n"
            f"!:"
        )
        # vwu > vtu
        frags[f"{r1}_gt_{r2}_then_la1.asm"] = (
            f"lda {rv2}+2\nbne !+\n"
            f"lda {rv1}+1\ncmp {rv2}+1\nbcc !+\nbne {{la1}}\n"
            f"lda {rv1}\ncmp {rv2}\nbeq !+\nbcs {{la1}}\n"
            f"!:"
        )
        # vwu < vtu
        frags[f"{r1}_lt_{r2}_then_la1.asm"] = (
            f"lda {rv2}+2\nbne {{la1}}\n"
            f"lda {rv1}+1\ncmp {rv2}+1\nbcc {{la1}}\nbne !+\n"
            f"lda {rv1}\ncmp {rv2}\nbcc {{la1}}\n"
            f"!:"
        )
        # vwu <= vtu
        frags[f"{r1}_le_{r2}_then_la1.asm"] = (
            f"lda {rv2}+2\nbne {{la1}}\n"
            f"lda {rv1}+1\ncmp {rv2}+1\nbcc {{la1}}\nbne !+\n"
            f"lda {rv1}\ncmp {rv2}\nbcc {{la1}}\nbeq {{la1}}\n"
            f"!:"
        )
        # vwu == vtu
        frags[f"{r1}_eq_{r2}_then_la1.asm"] = (
            f"lda {rv2}+2\nbne !+\n"
            f"lda {rv1}+1\ncmp {rv2}+1\nbne !+\n"
            f"lda {rv1}\ncmp {rv2}\nbeq {{la1}}\n"
            f"!:"
        )
        # vwu != vtu
        frags[f"{r1}_neq_{r2}_then_la1.asm"] = (
            f"lda {rv2}+2\nbne {{la1}}\n"
            f"lda {rv1}+1\ncmp {rv2}+1\nbne {{la1}}\n"
            f"lda {rv1}\ncmp {rv2}\nbne {{la1}}"
        )

    # 2. vtu vs vdu (24-bit vs 32-bit)
    for l1, l2 in locs2:
        s1 = f"vtu{l1}1"
        s2 = f"vdu{l2}2"
        v1 = f"{{{l1}1}}"
        v2 = f"{{{l2}2}}"

        # vtu >= vdu
        frags[f"{s1}_ge_{s2}_then_la1.asm"] = (
            f"lda {v2}+3\nbne !+\n"
            f"lda {v1}+2\ncmp {v2}+2\nbcc !+\nbne {{la1}}\n"
            f"lda {v1}+1\ncmp {v2}+1\nbcc !+\nbne {{la1}}\n"
            f"lda {v1}\ncmp {v2}\nbcs {{la1}}\n"
            f"!:"
        )
        # vtu > vdu
        frags[f"{s1}_gt_{s2}_then_la1.asm"] = (
            f"lda {v2}+3\nbne !+\n"
            f"lda {v1}+2\ncmp {v2}+2\nbcc !+\nbne {{la1}}\n"
            f"lda {v1}+1\ncmp {v2}+1\nbcc !+\nbne {{la1}}\n"
            f"lda {v1}\ncmp {v2}\nbeq !+\nbcs {{la1}}\n"
            f"!:"
        )
        # vtu < vdu
        frags[f"{s1}_lt_{s2}_then_la1.asm"] = (
            f"lda {v2}+3\nbne {{la1}}\n"
            f"lda {v1}+2\ncmp {v2}+2\nbcc {{la1}}\nbne !+\n"
            f"lda {v1}+1\ncmp {v2}+1\nbcc {{la1}}\nbne !+\n"
            f"lda {v1}\ncmp {v2}\nbcc {{la1}}\n"
            f"!:"
        )
        # vtu <= vdu
        frags[f"{s1}_le_{s2}_then_la1.asm"] = (
            f"lda {v2}+3\nbne {{la1}}\n"
            f"lda {v1}+2\ncmp {v2}+2\nbcc {{la1}}\nbne !+\n"
            f"lda {v1}+1\ncmp {v2}+1\nbcc {{la1}}\nbne !+\n"
            f"lda {v1}\ncmp {v2}\nbcc {{la1}}\nbeq {{la1}}\n"
            f"!:"
        )
        # vtu == vdu
        frags[f"{s1}_eq_{s2}_then_la1.asm"] = (
            f"lda {v2}+3\nbne !+\n"
            f"lda {v1}+2\ncmp {v2}+2\nbne !+\n"
            f"lda {v1}+1\ncmp {v2}+1\nbne !+\n"
            f"lda {v1}\ncmp {v2}\nbeq {{la1}}\n"
            f"!:"
        )
        # vtu != vdu
        frags[f"{s1}_neq_{s2}_then_la1.asm"] = (
            f"lda {v2}+3\nbne {{la1}}\n"
            f"lda {v1}+2\ncmp {v2}+2\nbne {{la1}}\n"
            f"lda {v1}+1\ncmp {v2}+1\nbne {{la1}}\n"
            f"lda {v1}\ncmp {v2}\nbne {{la1}}"
        )

        # Reverse: vdu vs vtu
        r1 = f"vdu{l1}1"
        r2 = f"vtu{l2}2"
        rv1 = f"{{{l1}1}}"
        rv2 = f"{{{l2}2}}"

        # vdu >= vtu
        frags[f"{r1}_ge_{r2}_then_la1.asm"] = (
            f"lda {rv1}+3\nbne {{la1}}\n"
            f"lda {rv1}+2\ncmp {rv2}+2\nbcc !+\nbne {{la1}}\n"
            f"lda {rv1}+1\ncmp {rv2}+1\nbcc !+\nbne {{la1}}\n"
            f"lda {rv1}\ncmp {rv2}\nbcs {{la1}}\n"
            f"!:"
        )
        # vdu > vtu
        frags[f"{r1}_gt_{r2}_then_la1.asm"] = (
            f"lda {rv1}+3\nbne {{la1}}\n"
            f"lda {rv1}+2\ncmp {rv2}+2\nbcc !+\nbne {{la1}}\n"
            f"lda {rv1}+1\ncmp {rv2}+1\nbcc !+\nbne {{la1}}\n"
            f"lda {rv1}\ncmp {rv2}\nbeq !+\nbcs {{la1}}\n"
            f"!:"
        )
        # vdu < vtu
        frags[f"{r1}_lt_{r2}_then_la1.asm"] = (
            f"lda {rv1}+3\nbne !+\n"
            f"lda {rv1}+2\ncmp {rv2}+2\nbcc {{la1}}\nbne !+\n"
            f"lda {rv1}+1\ncmp {rv2}+1\nbcc {{la1}}\nbne !+\n"
            f"lda {rv1}\ncmp {rv2}\nbcc {{la1}}\n"
            f"!:"
        )
        # vdu <= vtu
        frags[f"{r1}_le_{r2}_then_la1.asm"] = (
            f"lda {rv1}+3\nbne !+\n"
            f"lda {rv1}+2\ncmp {rv2}+2\nbcc {{la1}}\nbne !+\n"
            f"lda {rv1}+1\ncmp {rv2}+1\nbcc {{la1}}\nbne !+\n"
            f"lda {rv1}\ncmp {rv2}\nbcc {{la1}}\nbeq {{la1}}\n"
            f"!:"
        )
        # vdu == vtu
        frags[f"{r1}_eq_{r2}_then_la1.asm"] = (
            f"lda {rv1}+3\nbne !+\n"
            f"lda {rv1}+2\ncmp {rv2}+2\nbne !+\n"
            f"lda {rv1}+1\ncmp {rv2}+1\nbne !+\n"
            f"lda {rv1}\ncmp {rv2}\nbeq {{la1}}\n"
            f"!:"
        )
        # vdu != vtu
        frags[f"{r1}_neq_{r2}_then_la1.asm"] = (
            f"lda {rv1}+3\nbne {{la1}}\n"
            f"lda {rv1}+2\ncmp {rv2}+2\nbne {{la1}}\n"
            f"lda {rv1}+1\ncmp {rv2}+1\nbne {{la1}}\n"
            f"lda {rv1}\ncmp {rv2}\nbne {{la1}}"
        )

    # 3. vtu vs constants (vwuc, vbuc, vtuc, vduc, and signed variants, for both c1 and c2)
    for loc in ['z', 'm']:
        s1 = f"vtu{loc}1"
        v1 = f"{{{loc}1}}"
        
        for c_idx in ['1', '2']:
            c_ph = f"{{c{c_idx}}}"
            
            # 3.1 vtu vs vtuc / vtsc
            for c_prefix in [f"vtuc{c_idx}", f"vtsc{c_idx}"]:
                frags[f"{s1}_ge_{c_prefix}_then_la1.asm"] = (
                    f"lda {v1}+2\ncmp #<({c_ph}>>$10)\nbcc !+\nbne {{la1}}\n"
                    f"lda {v1}+1\ncmp #>{c_ph}\nbcc !+\nbne {{la1}}\n"
                    f"lda {v1}\ncmp #<{c_ph}\nbcs {{la1}}\n"
                    f"!:"
                )
                frags[f"{s1}_gt_{c_prefix}_then_la1.asm"] = (
                    f"lda {v1}+2\ncmp #<({c_ph}>>$10)\nbcc !+\nbne {{la1}}\n"
                    f"lda {v1}+1\ncmp #>{c_ph}\nbcc !+\nbne {{la1}}\n"
                    f"lda {v1}\ncmp #<{c_ph}\nbeq !+\nbcs {{la1}}\n"
                    f"!:"
                )
                frags[f"{s1}_lt_{c_prefix}_then_la1.asm"] = (
                    f"lda {v1}+2\ncmp #<({c_ph}>>$10)\nbcc {{la1}}\nbne !+\n"
                    f"lda {v1}+1\ncmp #>{c_ph}\nbcc {{la1}}\nbne !+\n"
                    f"lda {v1}\ncmp #<{c_ph}\nbcc {{la1}}\n"
                    f"!:"
                )
                frags[f"{s1}_le_{c_prefix}_then_la1.asm"] = (
                    f"lda {v1}+2\ncmp #<({c_ph}>>$10)\nbcc {{la1}}\nbne !+\n"
                    f"lda {v1}+1\ncmp #>{c_ph}\nbcc {{la1}}\nbne !+\n"
                    f"lda {v1}\ncmp #<{c_ph}\nbcc {{la1}}\nbeq {{la1}}\n"
                    f"!:"
                )
                frags[f"{s1}_eq_{c_prefix}_then_la1.asm"] = (
                    f"lda {v1}+2\ncmp #<({c_ph}>>$10)\nbne !+\n"
                    f"lda {v1}+1\ncmp #>{c_ph}\nbne !+\n"
                    f"lda {v1}\ncmp #<{c_ph}\nbeq {{la1}}\n"
                    f"!:"
                )
                frags[f"{s1}_neq_{c_prefix}_then_la1.asm"] = (
                    f"lda {v1}+2\ncmp #<({c_ph}>>$10)\nbne {{la1}}\n"
                    f"lda {v1}+1\ncmp #>{c_ph}\nbne {{la1}}\n"
                    f"lda {v1}\ncmp #<{c_ph}\nbne {{la1}}"
                )

            # 3.2 vtu vs vwuc / vwsc
            for c_prefix in [f"vwuc{c_idx}", f"vwsc{c_idx}"]:
                frags[f"{s1}_ge_{c_prefix}_then_la1.asm"] = (
                    f"lda {v1}+2\nbne {{la1}}\n"
                    f"lda {v1}+1\ncmp #>{c_ph}\nbcc !+\nbne {{la1}}\n"
                    f"lda {v1}\ncmp #<{c_ph}\nbcs {{la1}}\n"
                    f"!:"
                )
                frags[f"{s1}_gt_{c_prefix}_then_la1.asm"] = (
                    f"lda {v1}+2\nbne {{la1}}\n"
                    f"lda {v1}+1\ncmp #>{c_ph}\nbcc !+\nbne {{la1}}\n"
                    f"lda {v1}\ncmp #<{c_ph}\nbeq !+\nbcs {{la1}}\n"
                    f"!:"
                )
                frags[f"{s1}_lt_{c_prefix}_then_la1.asm"] = (
                    f"lda {v1}+2\nbne !+\n"
                    f"lda {v1}+1\ncmp #>{c_ph}\nbcc {{la1}}\nbne !+\n"
                    f"lda {v1}\ncmp #<{c_ph}\nbcc {{la1}}\n"
                    f"!:"
                )
                frags[f"{s1}_le_{c_prefix}_then_la1.asm"] = (
                    f"lda {v1}+2\nbne !+\n"
                    f"lda {v1}+1\ncmp #>{c_ph}\nbcc {{la1}}\nbne !+\n"
                    f"lda {v1}\ncmp #<{c_ph}\nbcc {{la1}}\nbeq {{la1}}\n"
                    f"!:"
                )
                frags[f"{s1}_eq_{c_prefix}_then_la1.asm"] = (
                    f"lda {v1}+2\nbne !+\n"
                    f"lda {v1}+1\ncmp #>{c_ph}\nbne !+\n"
                    f"lda {v1}\ncmp #<{c_ph}\nbeq {{la1}}\n"
                    f"!:"
                )
                frags[f"{s1}_neq_{c_prefix}_then_la1.asm"] = (
                    f"lda {v1}+2\nbne {{la1}}\n"
                    f"lda {v1}+1\ncmp #>{c_ph}\nbne {{la1}}\n"
                    f"lda {v1}\ncmp #<{c_ph}\nbne {{la1}}"
                )

            # 3.3 vtu vs vbuc / vbsc
            for c_prefix in [f"vbuc{c_idx}", f"vbsc{c_idx}"]:
                frags[f"{s1}_ge_{c_prefix}_then_la1.asm"] = (
                    f"lda {v1}+2\nbne {{la1}}\n"
                    f"lda {v1}+1\nbne {{la1}}\n"
                    f"lda {v1}\ncmp #{c_ph}\nbcs {{la1}}"
                )
                frags[f"{s1}_gt_{c_prefix}_then_la1.asm"] = (
                    f"lda {v1}+2\nbne {{la1}}\n"
                    f"lda {v1}+1\nbne {{la1}}\n"
                    f"lda {v1}\ncmp #{c_ph}\nbeq !+\nbcs {{la1}}\n"
                    f"!:"
                )
                frags[f"{s1}_lt_{c_prefix}_then_la1.asm"] = (
                    f"lda {v1}+2\nbne !+\n"
                    f"lda {v1}+1\nbne !+\n"
                    f"lda {v1}\ncmp #{c_ph}\nbcc {{la1}}\n"
                    f"!:"
                )
                frags[f"{s1}_le_{c_prefix}_then_la1.asm"] = (
                    f"lda {v1}+2\nbne !+\n"
                    f"lda {v1}+1\nbne !+\n"
                    f"lda {v1}\ncmp #{c_ph}\nbcc {{la1}}\nbeq {{la1}}\n"
                    f"!:"
                )
                frags[f"{s1}_eq_{c_prefix}_then_la1.asm"] = (
                    f"lda {v1}+2\nbne !+\n"
                    f"lda {v1}+1\nbne !+\n"
                    f"lda {v1}\ncmp #{c_ph}\nbeq {{la1}}\n"
                    f"!:"
                )
                frags[f"{s1}_neq_{c_prefix}_then_la1.asm"] = (
                    f"lda {v1}+2\nbne {{la1}}\n"
                    f"lda {v1}+1\nbne {{la1}}\n"
                    f"lda {v1}\ncmp #{c_ph}\nbne {{la1}}"
                )

            # 3.4 vtu vs vduc / vdsc
            for c_prefix in [f"vduc{c_idx}", f"vdsc{c_idx}"]:
                frags[f"{s1}_ge_{c_prefix}_then_la1.asm"] = (
                    f"lda #<({c_ph}>>$18)\nbne !+\n"
                    f"lda {v1}+2\ncmp #<({c_ph}>>$10)\nbcc !+\nbne {{la1}}\n"
                    f"lda {v1}+1\ncmp #>{c_ph}\nbcc !+\nbne {{la1}}\n"
                    f"lda {v1}\ncmp #<{c_ph}\nbcs {{la1}}\n"
                    f"!:"
                )
                frags[f"{s1}_gt_{c_prefix}_then_la1.asm"] = (
                    f"lda #<({c_ph}>>$18)\nbne !+\n"
                    f"lda {v1}+2\ncmp #<({c_ph}>>$10)\nbcc !+\nbne {{la1}}\n"
                    f"lda {v1}+1\ncmp #>{c_ph}\nbcc !+\nbne {{la1}}\n"
                    f"lda {v1}\ncmp #<{c_ph}\nbeq !+\nbcs {{la1}}\n"
                    f"!:"
                )
                frags[f"{s1}_lt_{c_prefix}_then_la1.asm"] = (
                    f"lda #<({c_ph}>>$18)\nbne {{la1}}\n"
                    f"lda {v1}+2\ncmp #<({c_ph}>>$10)\nbcc {{la1}}\nbne !+\n"
                    f"lda {v1}+1\ncmp #>{c_ph}\nbcc {{la1}}\nbne !+\n"
                    f"lda {v1}\ncmp #<{c_ph}\nbcc {{la1}}\n"
                    f"!:"
                )
                frags[f"{s1}_le_{c_prefix}_then_la1.asm"] = (
                    f"lda #<({c_ph}>>$18)\nbne {{la1}}\n"
                    f"lda {v1}+2\ncmp #<({c_ph}>>$10)\nbcc {{la1}}\nbne !+\n"
                    f"lda {v1}+1\ncmp #>{c_ph}\nbcc {{la1}}\nbne !+\n"
                    f"lda {v1}\ncmp #<{c_ph}\nbcc {{la1}}\nbeq {{la1}}\n"
                    f"!:"
                )
                frags[f"{s1}_eq_{c_prefix}_then_la1.asm"] = (
                    f"lda #<({c_ph}>>$18)\nbne !+\n"
                    f"lda {v1}+2\ncmp #<({c_ph}>>$10)\nbne !+\n"
                    f"lda {v1}+1\ncmp #>{c_ph}\nbne !+\n"
                    f"lda {v1}\ncmp #<{c_ph}\nbeq {{la1}}\n"
                    f"!:"
                )
                frags[f"{s1}_neq_{c_prefix}_then_la1.asm"] = (
                    f"lda #<({c_ph}>>$18)\nbne {{la1}}\n"
                    f"lda {v1}+2\ncmp #<({c_ph}>>$10)\nbne {{la1}}\n"
                    f"lda {v1}+1\ncmp #>{c_ph}\nbne {{la1}}\n"
                    f"lda {v1}\ncmp #<{c_ph}\nbne {{la1}}"
                )

    # 2.20 Zero Comparisons (eq_0, neq_0, gt_0, le_0)
    for loc in ['z', 'm']:
        s = f"vtu{loc}1"
        v = f"{{{loc}1}}"
        frags[f"{s}_eq_0_then_la1.asm"] = f"lda {v}\nora {v}+1\nora {v}+2\nbeq {{la1}}"
        frags[f"{s}_neq_0_then_la1.asm"] = f"lda {v}\nora {v}+1\nora {v}+2\nbne {{la1}}"
        frags[f"{s}_gt_0_then_la1.asm"] = f"lda {v}\nora {v}+1\nora {v}+2\nbne {{la1}}"
        frags[f"{s}_le_0_then_la1.asm"] = f"lda {v}\nora {v}+1\nora {v}+2\nbeq {{la1}}"
        
        ss = f"vts{loc}1"
        frags[f"{ss}_eq_0_then_la1.asm"] = frags[f"{s}_eq_0_then_la1.asm"]
        frags[f"{ss}_neq_0_then_la1.asm"] = frags[f"{s}_neq_0_then_la1.asm"]

    # 2.21 Tribyte Increments and Decrements (+1, -1)
    for loc in ['z', 'm']:
        d = f"vtu{loc}1"
        v = f"{{{loc}1}}"
        frags[f"{d}={d}_plus_1.asm"] = f"inc {v}\nbne !+\ninc {v}+1\nbne !+\ninc {v}+2\n!:"
        frags[f"{d}_plus_1.asm"] = frags[f"{d}={d}_plus_1.asm"]
        frags[f"{d}={d}_minus_1.asm"] = f"sec\nlda {v}\nsbc #1\nsta {v}\nlda {v}+1\nsbc #0\nsta {v}+1\nlda {v}+2\nsbc #0\nsta {v}+2"
        frags[f"{d}_minus_1.asm"] = frags[f"{d}={d}_minus_1.asm"]
        
    for l1, l2 in locs2:
        d = f"vtu{l1}1"
        s = f"vtu{l2}2"
        vd = f"{{{l1}1}}"
        vs = f"{{{l2}2}}"
        frags[f"{d}={s}_plus_1.asm"] = f"clc\nlda {vs}\nadc #1\nsta {vd}\nlda {vs}+1\nadc #0\nsta {vd}+1\nlda {vs}+2\nadc #0\nsta {vd}+2"
        frags[f"{d}={s}_minus_1.asm"] = f"sec\nlda {vs}\nsbc #1\nsta {vd}\nlda {vs}+1\nsbc #0\nsta {vd}+1\nlda {vs}+2\nsbc #0\nsta {vd}+2"

    # 2.22 Mixed-Width Additions and Subtractions (3-address and in-place)
    def make_add_asm(d_loc, s1_loc, s2_loc, d_bytes, s1_bytes, s2_bytes):
        lines = ["clc"]
        for b in range(d_bytes):
            if b < s1_bytes:
                off1 = f"+{b}" if b > 0 else ""
                lines.append(f"lda {{{s1_loc}2}}{off1}")
            else:
                lines.append("lda #0")
            if b < s2_bytes:
                off2 = f"+{b}" if b > 0 else ""
                lines.append(f"adc {{{s2_loc}3}}{off2}")
            else:
                lines.append("adc #0")
            offd = f"+{b}" if b > 0 else ""
            lines.append(f"sta {{{d_loc}1}}{offd}")
        return "\n".join(lines)

    def make_sub_asm(d_loc, s1_loc, s2_loc, d_bytes, s1_bytes, s2_bytes):
        lines = ["sec"]
        for b in range(d_bytes):
            if b < s1_bytes:
                off1 = f"+{b}" if b > 0 else ""
                lines.append(f"lda {{{s1_loc}2}}{off1}")
            else:
                lines.append("lda #0")
            if b < s2_bytes:
                off2 = f"+{b}" if b > 0 else ""
                lines.append(f"sbc {{{s2_loc}3}}{off2}")
            else:
                lines.append("sbc #0")
            offd = f"+{b}" if b > 0 else ""
            lines.append(f"sta {{{d_loc}1}}{offd}")
        return "\n".join(lines)

    type_info = {
        'vbu': 1,
        'vwu': 2,
        'vtu': 3,
        'vdu': 4
    }

    all_types = ['vbu', 'vwu', 'vtu', 'vdu']
    mixed_combos = []
    for dt in ['vtu', 'vdu']:
        for s1t in all_types:
            for s2t in all_types:
                if 'vtu' in (dt, s1t, s2t):
                    mixed_combos.append((dt, s1t, s2t))

    locs3 = [
        ('z', 'z', 'z'), ('z', 'z', 'm'), ('z', 'm', 'z'), ('z', 'm', 'm'),
        ('m', 'z', 'z'), ('m', 'z', 'm'), ('m', 'm', 'z'), ('m', 'm', 'm')
    ]

    for dt, s1t, s2t in mixed_combos:
        db = type_info[dt]
        s1b = type_info[s1t]
        s2b = type_info[s2t]
        for dl, s1l, s2l in locs3:
            d_name = f"{dt}{dl}1"
            s1_name = f"{s1t}{s1l}2"
            s2_name = f"{s2t}{s2l}3"
            frags[f"{d_name}={s1_name}_plus_{s2_name}.asm"] = make_add_asm(dl, s1l, s2l, db, s1b, s2b)
            frags[f"{d_name}={s1_name}_minus_{s2_name}.asm"] = make_sub_asm(dl, s1l, s2l, db, s1b, s2b)

    def make_inplace_add_asm(d_loc, s_loc, d_bytes, s_bytes):
        lines = ["clc"]
        for b in range(d_bytes):
            offd = f"+{b}" if b > 0 else ""
            lines.append(f"lda {{{d_loc}1}}{offd}")
            if b < s_bytes:
                offs = f"+{b}" if b > 0 else ""
                lines.append(f"adc {{{s_loc}2}}{offs}")
            else:
                lines.append("adc #0")
            lines.append(f"sta {{{d_loc}1}}{offd}")
        return "\n".join(lines)

    def make_inplace_sub_asm(d_loc, s_loc, d_bytes, s_bytes):
        lines = ["sec"]
        for b in range(d_bytes):
            offd = f"+{b}" if b > 0 else ""
            lines.append(f"lda {{{d_loc}1}}{offd}")
            if b < s_bytes:
                offs = f"+{b}" if b > 0 else ""
                lines.append(f"sbc {{{s_loc}2}}{offs}")
            else:
                lines.append("sbc #0")
            lines.append(f"sta {{{d_loc}1}}{offd}")
        return "\n".join(lines)

    inplace_combos = []
    for dt in ['vtu', 'vdu']:
        for st in all_types:
            if 'vtu' in (dt, st) and dt != st:
                inplace_combos.append((dt, st))

    for dt, st in inplace_combos:
        db = type_info[dt]
        sb = type_info[st]
        for dl, sl in locs2:
            d_name = f"{dt}{dl}1"
            s_name = f"{st}{sl}2"
            frags[f"{d_name}={d_name}_plus_{s_name}.asm"] = make_inplace_add_asm(dl, sl, db, sb)
            frags[f"{d_name}={d_name}_minus_{s_name}.asm"] = make_inplace_sub_asm(dl, sl, db, sb)

    # 2.23 Cross-Type Constant Assignments (vtu=vduc, vtu=vwuc, vtu=vbuc, vdu=vtuc, etc.)
    for loc in ['z', 'm']:
        # vtu = vduc / vdsc
        frags[f"vtu{loc}1=vduc1.asm"] = f"lda #<{{c1}}\nsta {{{loc}1}}\nlda #>{{c1}}\nsta {{{loc}1}}+1\nlda #<{{c1}}>>$10\nsta {{{loc}1}}+2"
        frags[f"vtu{loc}1=vdsc1.asm"] = frags[f"vtu{loc}1=vduc1.asm"]
        frags[f"vts{loc}1=vduc1.asm"] = frags[f"vtu{loc}1=vduc1.asm"]
        frags[f"vts{loc}1=vdsc1.asm"] = frags[f"vtu{loc}1=vduc1.asm"]

        # vtu = vwuc / vwsc
        frags[f"vtu{loc}1=vwuc1.asm"] = f"lda #<{{c1}}\nsta {{{loc}1}}\nlda #>{{c1}}\nsta {{{loc}1}}+1\nlda #0\nsta {{{loc}1}}+2"
        frags[f"vtu{loc}1=vwsc1.asm"] = frags[f"vtu{loc}1=vwuc1.asm"]
        frags[f"vts{loc}1=vwuc1.asm"] = frags[f"vtu{loc}1=vwuc1.asm"]
        frags[f"vts{loc}1=vwsc1.asm"] = frags[f"vtu{loc}1=vwuc1.asm"]

        # vtu = vbuc / vbsc
        frags[f"vtu{loc}1=vbuc1.asm"] = f"lda #{{c1}}\nsta {{{loc}1}}\nlda #0\nsta {{{loc}1}}+1\nsta {{{loc}1}}+2"
        frags[f"vtu{loc}1=vbsc1.asm"] = frags[f"vtu{loc}1=vbuc1.asm"]
        frags[f"vts{loc}1=vbuc1.asm"] = frags[f"vtu{loc}1=vbuc1.asm"]
        frags[f"vts{loc}1=vbsc1.asm"] = frags[f"vtu{loc}1=vbuc1.asm"]

        # vdu = vtuc / vtsc
        frags[f"vdu{loc}1=vtuc1.asm"] = f"lda #<{{c1}}\nsta {{{loc}1}}\nlda #>{{c1}}\nsta {{{loc}1}}+1\nlda #<{{c1}}>>$10\nsta {{{loc}1}}+2\nlda #0\nsta {{{loc}1}}+3"
        frags[f"vdu{loc}1=vtsc1.asm"] = frags[f"vdu{loc}1=vtuc1.asm"]
        frags[f"vds{loc}1=vtuc1.asm"] = frags[f"vdu{loc}1=vtuc1.asm"]
        frags[f"vds{loc}1=vtsc1.asm"] = frags[f"vdu{loc}1=vtuc1.asm"]

        # vduz1 = vduc1 / vdsc1
        frags[f"vdu{loc}1=vduc1.asm"] = f"lda #<{{c1}}\nsta {{{loc}1}}\nlda #>{{c1}}\nsta {{{loc}1}}+1\nlda #<{{c1}}>>$10\nsta {{{loc}1}}+2\nlda #>{{c1}}>>$10\nsta {{{loc}1}}+3"
        frags[f"vdu{loc}1=vdsc1.asm"] = frags[f"vdu{loc}1=vduc1.asm"]
        frags[f"vds{loc}1=vduc1.asm"] = frags[f"vdu{loc}1=vduc1.asm"]
        frags[f"vds{loc}1=vdsc1.asm"] = frags[f"vdu{loc}1=vduc1.asm"]

        # vtu vs vduc2 comparisons
        s1 = f"vtu{loc}1"
        v1 = f"{{{loc}1}}"
        frags[f"{s1}_ge_vduc2_then_la1.asm"] = (
            f"lda #<({{c2}}>>$18)\nbne !+\n"
            f"lda {v1}+2\ncmp #<({{c2}}>>$10)\nbcc !+\nbne {{la1}}\n"
            f"lda {v1}+1\ncmp #>{{c2}}\nbcc !+\nbne {{la1}}\n"
            f"lda {v1}\ncmp #<{{c2}}\nbcs {{la1}}\n"
            f"!:"
        )
        frags[f"{s1}_gt_vduc2_then_la1.asm"] = (
            f"lda #<({{c2}}>>$18)\nbne !+\n"
            f"lda {v1}+2\ncmp #<({{c2}}>>$10)\nbcc !+\nbne {{la1}}\n"
            f"lda {v1}+1\ncmp #>{{c2}}\nbcc !+\nbne {{la1}}\n"
            f"lda {v1}\ncmp #<{{c2}}\nbeq !+\nbcs {{la1}}\n"
            f"!:"
        )
        frags[f"{s1}_lt_vduc2_then_la1.asm"] = (
            f"lda #<({{c2}}>>$18)\nbne {{la1}}\n"
            f"lda {v1}+2\ncmp #<({{c2}}>>$10)\nbcc {{la1}}\nbne !+\n"
            f"lda {v1}+1\ncmp #>{{c2}}\nbcc {{la1}}\nbne !+\n"
            f"lda {v1}\ncmp #<{{c2}}\nbcc {{la1}}\n"
            f"!:"
        )
        frags[f"{s1}_le_vduc2_then_la1.asm"] = (
            f"lda #<({{c2}}>>$18)\nbne {{la1}}\n"
            f"lda {v1}+2\ncmp #<({{c2}}>>$10)\nbcc {{la1}}\nbne !+\n"
            f"lda {v1}+1\ncmp #>{{c2}}\nbcc {{la1}}\nbne !+\n"
            f"lda {v1}\ncmp #<{{c2}}\nbcc {{la1}}\nbeq {{la1}}\n"
            f"!:"
        )
        frags[f"{s1}_eq_vduc2_then_la1.asm"] = (
            f"lda #<({{c2}}>>$18)\nbne !+\n"
            f"lda {v1}+2\ncmp #<({{c2}}>>$10)\nbne !+\n"
            f"lda {v1}+1\ncmp #>{{c2}}\nbne !+\n"
            f"lda {v1}\ncmp #<{{c2}}\nbeq {{la1}}\n"
            f"!:"
        )
        frags[f"{s1}_neq_vduc2_then_la1.asm"] = (
            f"lda #<({{c2}}>>$18)\nbne {{la1}}\n"
            f"lda {v1}+2\ncmp #<({{c2}}>>$10)\nbne {{la1}}\n"
            f"lda {v1}+1\ncmp #>{{c2}}\nbne {{la1}}\n"
            f"lda {v1}\ncmp #<{{c2}}\nbne {{la1}}"
        )


    # 2.24 _deref_ptuc and vtu comparisons against indexed arrays and variables
    # (e.g. _deref_ptuc1_ge_pwuc2_derefidx_vbuz1_then_la1)
    
    idx_variants = [
        ('vbuz', 'ldy {z_idx}\n', ',y'),
        ('vbum', 'ldy {m_idx}\n', ',y'),
        ('vbuxx', '', ',x'),
        ('vbuyy', '', ',y'),
    ]

    for idx_name, idx_setup, idx_reg in idx_variants:
        idx_var = f"{idx_name}1" if idx_name in ['vbuz', 'vbum'] else idx_name
        setup_line = idx_setup.replace("{z_idx}", "{z1}").replace("{m_idx}", "{m1}")
        
        # _deref_ptuc1 vs pwuc2_derefidx_{idx_var}
        # GE
        frags[f"_deref_ptuc1_ge_pwuc2_derefidx_{idx_var}_then_la1.asm"] = (
            f"{setup_line}"
            f"lda {{c1}}+2\nbne {{la1}}\n"
            f"lda {{c1}}+1\ncmp {{c2}}+1{idx_reg}\nbcc !+\nbne {{la1}}\n"
            f"lda {{c1}}\ncmp {{c2}}{idx_reg}\nbcs {{la1}}\n"
            f"!:"
        )
        # LT
        frags[f"_deref_ptuc1_lt_pwuc2_derefidx_{idx_var}_then_la1.asm"] = (
            f"{setup_line}"
            f"lda {{c1}}+2\nbne !+\n"
            f"lda {{c1}}+1\ncmp {{c2}}+1{idx_reg}\nbcc {{la1}}\nbne !+\n"
            f"lda {{c1}}\ncmp {{c2}}{idx_reg}\nbcc {{la1}}\n"
            f"!:"
        )
        # GT
        frags[f"_deref_ptuc1_gt_pwuc2_derefidx_{idx_var}_then_la1.asm"] = (
            f"{setup_line}"
            f"lda {{c1}}+2\nbne {{la1}}\n"
            f"lda {{c1}}+1\ncmp {{c2}}+1{idx_reg}\nbcc !+\nbne {{la1}}\n"
            f"lda {{c1}}\ncmp {{c2}}{idx_reg}\nbeq !+\nbcs {{la1}}\n"
            f"!:"
        )
        # LE
        frags[f"_deref_ptuc1_le_pwuc2_derefidx_{idx_var}_then_la1.asm"] = (
            f"{setup_line}"
            f"lda {{c1}}+2\nbne !+\n"
            f"lda {{c1}}+1\ncmp {{c2}}+1{idx_reg}\nbcc {{la1}}\nbne !+\n"
            f"lda {{c1}}\ncmp {{c2}}{idx_reg}\nbcc {{la1}}\nbeq {{la1}}\n"
            f"!:"
        )
        # EQ
        frags[f"_deref_ptuc1_eq_pwuc2_derefidx_{idx_var}_then_la1.asm"] = (
            f"{setup_line}"
            f"lda {{c1}}+2\nbne !+\n"
            f"lda {{c1}}+1\ncmp {{c2}}+1{idx_reg}\nbne !+\n"
            f"lda {{c1}}\ncmp {{c2}}{idx_reg}\nbeq {{la1}}\n"
            f"!:"
        )
        # NEQ
        frags[f"_deref_ptuc1_neq_pwuc2_derefidx_{idx_var}_then_la1.asm"] = (
            f"{setup_line}"
            f"lda {{c1}}+2\nbne {{la1}}\n"
            f"lda {{c1}}+1\ncmp {{c2}}+1{idx_reg}\nbne {{la1}}\n"
            f"lda {{c1}}\ncmp {{c2}}{idx_reg}\nbne {{la1}}"
        )

        # Also for vtu variable vs pwuc1_derefidx_{idx_var}
        for loc in ['z', 'm']:
            s = f"vtu{loc}1"
            v = f"{{{loc}1}}"
            idx_v2 = f"{idx_name}2" if idx_name in ['vbuz', 'vbum'] else idx_name
            setup_l2 = idx_setup.replace("{z_idx}", "{z2}").replace("{m_idx}", "{m2}")

            # GE
            frags[f"{s}_ge_pwuc1_derefidx_{idx_v2}_then_la1.asm"] = (
                f"{setup_l2}"
                f"lda {v}+2\nbne {{la1}}\n"
                f"lda {v}+1\ncmp {{c1}}+1{idx_reg}\nbcc !+\nbne {{la1}}\n"
                f"lda {v}\ncmp {{c1}}{idx_reg}\nbcs {{la1}}\n"
                f"!:"
            )
            # LT
            frags[f"{s}_lt_pwuc1_derefidx_{idx_v2}_then_la1.asm"] = (
                f"{setup_l2}"
                f"lda {v}+2\nbne !+\n"
                f"lda {v}+1\ncmp {{c1}}+1{idx_reg}\nbcc {{la1}}\nbne !+\n"
                f"lda {v}\ncmp {{c1}}{idx_reg}\nbcc {{la1}}\n"
                f"!:"
            )
            # GT
            frags[f"{s}_gt_pwuc1_derefidx_{idx_v2}_then_la1.asm"] = (
                f"{setup_l2}"
                f"lda {v}+2\nbne {{la1}}\n"
                f"lda {v}+1\ncmp {{c1}}+1{idx_reg}\nbcc !+\nbne {{la1}}\n"
                f"lda {v}\ncmp {{c1}}{idx_reg}\nbeq !+\nbcs {{la1}}\n"
                f"!:"
            )
            # LE
            frags[f"{s}_le_pwuc1_derefidx_{idx_v2}_then_la1.asm"] = (
                f"{setup_l2}"
                f"lda {v}+2\nbne !+\n"
                f"lda {v}+1\ncmp {{c1}}+1{idx_reg}\nbcc {{la1}}\nbne !+\n"
                f"lda {v}\ncmp {{c1}}{idx_reg}\nbcc {{la1}}\nbeq {{la1}}\n"
                f"!:"
            )
            # EQ
            frags[f"{s}_eq_pwuc1_derefidx_{idx_v2}_then_la1.asm"] = (
                f"{setup_l2}"
                f"lda {v}+2\nbne !+\n"
                f"lda {v}+1\ncmp {{c1}}+1{idx_reg}\nbne !+\n"
                f"lda {v}\ncmp {{c1}}{idx_reg}\nbeq {{la1}}\n"
                f"!:"
            )
            # NEQ
            frags[f"{s}_neq_pwuc1_derefidx_{idx_v2}_then_la1.asm"] = (
                f"{setup_l2}"
                f"lda {v}+2\nbne {{la1}}\n"
                f"lda {v}+1\ncmp {{c1}}+1{idx_reg}\nbne {{la1}}\n"
                f"lda {v}\ncmp {{c1}}{idx_reg}\nbne {{la1}}"
            )

    # _deref_ptuc1 vs variables (vwuz, vwum, vtuz, vtum, vduz, vdum)
    for v_idx in ['1', '2']:
        for vloc in ['z', 'm']:
            # vs vwu
            frags[f"_deref_ptuc1_ge_vwu{vloc}{v_idx}_then_la1.asm"] = (
                f"lda {{c1}}+2\nbne {{la1}}\n"
                f"lda {{c1}}+1\ncmp {{{vloc}{v_idx}}}+1\nbcc !+\nbne {{la1}}\n"
                f"lda {{c1}}\ncmp {{{vloc}{v_idx}}}\nbcs {{la1}}\n"
                f"!:"
            )
            frags[f"_deref_ptuc1_lt_vwu{vloc}{v_idx}_then_la1.asm"] = (
                f"lda {{c1}}+2\nbne !+\n"
                f"lda {{c1}}+1\ncmp {{{vloc}{v_idx}}}+1\nbcc {{la1}}\nbne !+\n"
                f"lda {{c1}}\ncmp {{{vloc}{v_idx}}}\nbcc {{la1}}\n"
                f"!:"
            )
            frags[f"_deref_ptuc1_eq_vwu{vloc}{v_idx}_then_la1.asm"] = (
                f"lda {{c1}}+2\nbne !+\n"
                f"lda {{c1}}+1\ncmp {{{vloc}{v_idx}}}+1\nbne !+\n"
                f"lda {{c1}}\ncmp {{{vloc}{v_idx}}}\nbeq {{la1}}\n"
                f"!:"
            )
            frags[f"_deref_ptuc1_neq_vwu{vloc}{v_idx}_then_la1.asm"] = (
                f"lda {{c1}}+2\nbne {{la1}}\n"
                f"lda {{c1}}+1\ncmp {{{vloc}{v_idx}}}+1\nbne {{la1}}\n"
                f"lda {{c1}}\ncmp {{{vloc}{v_idx}}}\nbne {{la1}}"
            )
            
            # vs vtu
            frags[f"_deref_ptuc1_ge_vtu{vloc}{v_idx}_then_la1.asm"] = (
                f"lda {{c1}}+2\ncmp {{{vloc}{v_idx}}}+2\nbcc !+\nbne {{la1}}\n"
                f"lda {{c1}}+1\ncmp {{{vloc}{v_idx}}}+1\nbcc !+\nbne {{la1}}\n"
                f"lda {{c1}}\ncmp {{{vloc}{v_idx}}}\nbcs {{la1}}\n"
                f"!:"
            )
            frags[f"_deref_ptuc1_lt_vtu{vloc}{v_idx}_then_la1.asm"] = (
                f"lda {{c1}}+2\ncmp {{{vloc}{v_idx}}}+2\nbcc {{la1}}\nbne !+\n"
                f"lda {{c1}}+1\ncmp {{{vloc}{v_idx}}}+1\nbcc {{la1}}\nbne !+\n"
                f"lda {{c1}}\ncmp {{{vloc}{v_idx}}}\nbcc {{la1}}\n"
                f"!:"
            )
            frags[f"_deref_ptuc1_eq_vtu{vloc}{v_idx}_then_la1.asm"] = (
                f"lda {{c1}}+2\ncmp {{{vloc}{v_idx}}}+2\nbne !+\n"
                f"lda {{c1}}+1\ncmp {{{vloc}{v_idx}}}+1\nbne !+\n"
                f"lda {{c1}}\ncmp {{{vloc}{v_idx}}}\nbeq {{la1}}\n"
                f"!:"
            )
            frags[f"_deref_ptuc1_neq_vtu{vloc}{v_idx}_then_la1.asm"] = (
                f"lda {{c1}}+2\ncmp {{{vloc}{v_idx}}}+2\nbne {{la1}}\n"
                f"lda {{c1}}+1\ncmp {{{vloc}{v_idx}}}+1\nbne {{la1}}\n"
                f"lda {{c1}}\ncmp {{{vloc}{v_idx}}}\nbne {{la1}}"
            )


    # 2.25 Dynamic Pointer Indexed Stores & Loads (pdu and ptu pointers)
    # E.g. pduz1_derefidx_vbuc1=vtuz2
    
    ptr_types = [
        ('pduz', 4, 'z'),
        ('pdum', 4, 'm'),
        ('ptuz', 3, 'z'),
        ('ptum', 3, 'm'),
    ]

    idx_types_ptr = [
        ('vbuc', 'ldy #{c_idx}\n'),
        ('vbuxx', 'txa\ntay\n'),
        ('vbuyy', ''),
        ('vbuz', 'ldy {z_idx}\n'),
        ('vbum', 'ldy {m_idx}\n'),
    ]

    val_types_ptr = [
        ('vtuz', 3, 'z'),
        ('vtum', 3, 'm'),
        ('vduz', 4, 'z'),
        ('vdum', 4, 'm'),
    ]

    for p_prefix, p_bytes, p_loc in ptr_types:
        for idx_prefix, idx_setup in idx_types_ptr:
            idx_name = f"{idx_prefix}1" if idx_prefix in ['vbuc'] else (f"{idx_prefix}2" if idx_prefix in ['vbuz', 'vbum'] else idx_prefix)
            
            # STORES: {ptr}_derefidx_{idx} = {val}
            for v_prefix, v_bytes, v_loc in val_types_ptr:
                # If pointer is ZP or MEM
                # Determine value index
                v_num = "2" if idx_prefix in ['vbuc', 'vbuxx', 'vbuyy'] else "3"
                sig_store = f"{p_prefix}1_derefidx_{idx_name}={v_prefix}{v_num}"
                
                lines = []
                if p_loc == 'm':
                    lines.append("lda {m1}\nsta $fe\nlda {m1}+1\nsta $ff")
                    ptr_zp = "$fe"
                else:
                    ptr_zp = "{z1}"
                    
                if idx_setup:
                    setup_str = idx_setup.replace("{c_idx}", "{c1}").replace("{z_idx}", "{z2}").replace("{m_idx}", "{m2}")
                    lines.append(setup_str.strip())
                    
                # Store bytes
                val_ref = f"{{{v_loc}{v_num}}}"
                for b in range(p_bytes):
                    if b > 0:
                        lines.append("iny")
                    if b < v_bytes:
                        off = f"+{b}" if b > 0 else ""
                        lines.append(f"lda {val_ref}{off}")
                    else:
                        lines.append("lda #0")
                    lines.append(f"sta ({ptr_zp}),y")
                    
                frags[f"{sig_store}.asm"] = "\n".join(lines)

            # LOADS: {val} = {ptr}_derefidx_{idx}
            for v_prefix, v_bytes, v_loc in val_types_ptr:
                v_num = "1"
                p_num = "2"
                
                # Determine idx variable number for load
                if idx_prefix == 'vbuc':
                    idx_load_name = "vbuc1"
                elif idx_prefix in ['vbuz', 'vbum']:
                    idx_load_name = f"{idx_prefix}3"
                else:
                    idx_load_name = idx_prefix
                    
                sig_load = f"{v_prefix}{v_num}={p_prefix}{p_num}_derefidx_{idx_load_name}"
                
                lines = []
                if p_loc == 'm':
                    lines.append(f"lda {{{p_loc}{p_num}}}\nsta $fe\nlda {{{p_loc}{p_num}}}+1\nsta $ff")
                    ptr_zp = "$fe"
                else:
                    ptr_zp = f"{{{p_loc}{p_num}}}"
                    
                if idx_setup:
                    setup_str = idx_setup.replace("{c_idx}", "{c1}").replace("{z_idx}", "{z3}").replace("{m_idx}", "{m3}")
                    lines.append(setup_str.strip())
                    
                val_dst = f"{{{v_loc}{v_num}}}"
                for b in range(v_bytes):
                    if b > 0:
                        lines.append("iny")
                    if b < p_bytes:
                        lines.append(f"lda ({ptr_zp}),y")
                    else:
                        lines.append("lda #0")
                    off = f"+{b}" if b > 0 else ""
                    lines.append(f"sta {val_dst}{off}")
                    
                frags[f"{sig_load}.asm"] = "\n".join(lines)


    # Write all fragments
    tot_created = 0
    tot_updated = 0
    tot_unchanged = 0
    for name, asm in frags.items():
        c, u, un = write_fragment(name, asm, dirs)
        tot_created += c
        tot_updated += u
        tot_unchanged += un

    print(f"Generated {len(frags)} fragments.")
    print(f"Stats: Created: {tot_created}, Updated: {tot_updated}, Unchanged: {tot_unchanged}")

if __name__ == "__main__":
    main()
