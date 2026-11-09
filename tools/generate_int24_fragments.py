#!/usr/bin/env python3
"""
Tool: generate_int24_fragments.py
Generates the complete orthogonal matrix of 24-bit (vss / int24 / uint24)
and mixed-width (dword vs word / 24-bit) ASM fragments for KickC.
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
    print("=== Generating Orthogonal 24-Bit & Mixed-Width ASM Fragments ===\n")
    frags = {}

    locs = [('z', 'z'), ('z', 'm'), ('m', 'z'), ('m', 'm')]

    # 1. 24-bit Struct -> 32-bit Dword (Zero-extended): vdu = vss and vdu = _dword_vss
    for d_loc, s_loc in locs:
        d = f"vdu{d_loc}1"
        s = f"vss{s_loc}2"
        d_val = f"{{{d_loc}1}}"
        s_val = f"{{{s_loc}2}}"
        asm = (
            f"lda {s_val}\nsta {d_val}\n"
            f"lda {s_val}+1\nsta {d_val}+1\n"
            f"lda {s_val}+2\nsta {d_val}+2\n"
            f"lda #0\nsta {d_val}+3"
        )
        frags[f"{d}={s}.asm"] = asm
        frags[f"{d}=_dword_{s}.asm"] = asm

    # 2. 24-bit Struct -> 32-bit SDword (Sign-extended): vds = _sdword_vss
    for d_loc, s_loc in locs:
        d = f"vds{d_loc}1"
        s = f"vss{s_loc}2"
        d_val = f"{{{d_loc}1}}"
        s_val = f"{{{s_loc}2}}"
        asm = (
            f"lda {s_val}\nsta {d_val}\n"
            f"lda {s_val}+1\nsta {d_val}+1\n"
            f"lda {s_val}+2\nsta {d_val}+2\n"
            f"ora #$7f\nbmi !+\nlda #0\n!:\nsta {d_val}+3"
        )
        frags[f"{d}=_sdword_{s}.asm"] = asm

    # 3. 32-bit Dword -> 24-bit Struct (Truncated): vss = vdu
    for d_loc, s_loc in locs:
        d = f"vss{d_loc}1"
        s = f"vdu{s_loc}2"
        d_val = f"{{{d_loc}1}}"
        s_val = f"{{{s_loc}2}}"
        asm = (
            f"lda {s_val}\nsta {d_val}\n"
            f"lda {s_val}+1\nsta {d_val}+1\n"
            f"lda {s_val}+2\nsta {d_val}+2"
        )
        frags[f"{d}={s}.asm"] = asm

    # 4. 24-bit Struct -> 24-bit Struct (3 bytes copy): vss = vss
    for d_loc, s_loc in locs:
        d = f"vss{d_loc}1"
        s = f"vss{s_loc}2"
        d_val = f"{{{d_loc}1}}"
        s_val = f"{{{s_loc}2}}"
        asm = (
            f"lda {s_val}\nsta {d_val}\n"
            f"lda {s_val}+1\nsta {d_val}+1\n"
            f"lda {s_val}+2\nsta {d_val}+2"
        )
        frags[f"{d}={s}.asm"] = asm

    # 5. 16-bit Word -> 24-bit Struct (Zero-extended): vss = vwu
    for d_loc, s_loc in locs:
        d = f"vss{d_loc}1"
        s = f"vwu{s_loc}2"
        d_val = f"{{{d_loc}1}}"
        s_val = f"{{{s_loc}2}}"
        asm = (
            f"lda {s_val}\nsta {d_val}\n"
            f"lda {s_val}+1\nsta {d_val}+1\n"
            f"lda #0\nsta {d_val}+2"
        )
        frags[f"{d}={s}.asm"] = asm

    # 6. 24-bit Struct -> 16-bit Word (Truncated): vwu = vss and vwu = _word_vss
    for d_loc, s_loc in locs:
        d = f"vwu{d_loc}1"
        s = f"vss{s_loc}2"
        d_val = f"{{{d_loc}1}}"
        s_val = f"{{{s_loc}2}}"
        asm = (
            f"lda {s_val}\nsta {d_val}\n"
            f"lda {s_val}+1\nsta {d_val}+1"
        )
        frags[f"{d}={s}.asm"] = asm
        frags[f"{d}=_word_{s}.asm"] = asm

    # 7. 8-bit Byte -> 24-bit Struct: vss = vbu, vss = vbuaa
    for d_loc in ['z', 'm']:
        d = f"vss{d_loc}1"
        d_val = f"{{{d_loc}1}}"
        frags[f"{d}=vbuaa.asm"] = f"sta {d_val}\nlda #0\nsta {d_val}+1\nsta {d_val}+2"
        for s_loc in ['z', 'm']:
            s = f"vbu{s_loc}2"
            s_val = f"{{{s_loc}2}}"
            frags[f"{d}={s}.asm"] = f"lda {s_val}\nsta {d_val}\nlda #0\nsta {d_val}+1\nsta {d_val}+2"

    # 8. 24-bit Struct -> 8-bit Byte: vbuaa = vss, vbu = vss, vbuaa = _byte_vss
    for s_loc in ['z', 'm']:
        s = f"vss{s_loc}2"
        s_val = f"{{{s_loc}2}}"
        frags[f"vbuaa={s}.asm"] = f"lda {s_val}"
        frags[f"vbuaa=_byte_{s}.asm"] = f"lda {s_val}"
        for d_loc in ['z', 'm']:
            d = f"vbu{d_loc}1"
            d_val = f"{{{d_loc}1}}"
            frags[f"{d}={s}.asm"] = f"lda {s_val}\nsta {d_val}"
            frags[f"{d}=_byte_{s}.asm"] = f"lda {s_val}\nsta {d_val}"

    # 9. Pointer Dereferences (pssz, pssm -> 24-bit struct dereference)
    for d_loc in ['z', 'm']:
        d = f"vss{d_loc}1"
        d_val = f"{{{d_loc}1}}"
        # Indirect via ZP pointer
        frags[f"{d}=_deref_pssz2.asm"] = (
            f"ldy #0\nlda ({{z2}}),y\nsta {d_val}\n"
            f"iny\nlda ({{z2}}),y\nsta {d_val}+1\n"
            f"iny\nlda ({{z2}}),y\nsta {d_val}+2"
        )
        frags[f"{d}=pssz2_derefidx_vbuyy.asm"] = (
            f"lda ({{z2}}),y\nsta {d_val}\n"
            f"iny\nlda ({{z2}}),y\nsta {d_val}+1\n"
            f"iny\nlda ({{z2}}),y\nsta {d_val}+2"
        )
        # Store via ZP pointer
        frags[f"_deref_pssz1=vss{d_loc}2.asm"] = (
            f"ldy #0\nlda {{{d_loc}2}}\nsta ({{z1}}),y\n"
            f"iny\nlda {{{d_loc}2}}+1\nsta ({{z1}}),y\n"
            f"iny\nlda {{{d_loc}2}}+2\nsta ({{z1}}),y"
        )
        frags[f"pssz1_derefidx_vbuyy=vss{d_loc}2.asm"] = (
            f"lda {{{d_loc}2}}\nsta ({{z1}}),y\n"
            f"iny\nlda {{{d_loc}2}}+1\nsta ({{z1}}),y\n"
            f"iny\nlda {{{d_loc}2}}+2\nsta ({{z1}}),y"
        )

    # 10. Mixed 32-bit Dword / 16-bit Word Arithmetic: vdu = vdu + vwu, vdu = vdu - vwu
    for loc in ['z', 'm']:
        v1 = f"{{{loc}1}}"
        v2 = f"{{{loc}2}}"
        v3 = f"{{{loc}3}}"
        frags[f"vdu{loc}1=vdu{loc}2_plus_vwu{loc}3.asm"] = (
            f"clc\nlda {v2}\nadc {v3}\nsta {v1}\n"
            f"lda {v2}+1\nadc {v3}+1\nsta {v1}+1\n"
            f"lda {v2}+2\nadc #0\nsta {v1}+2\n"
            f"lda {v2}+3\nadc #0\nsta {v1}+3"
        )
        frags[f"vdu{loc}1=vdu{loc}1_plus_vwu{loc}2.asm"] = (
            f"clc\nlda {v1}\nadc {v2}\nsta {v1}\n"
            f"lda {v1}+1\nadc {v2}+1\nsta {v1}+1\n"
            f"bcc !+\ninc {v1}+2\nbne !+\ninc {v1}+3\n!:"
        )
        frags[f"vdu{loc}1=vdu{loc}2_minus_vwu{loc}3.asm"] = (
            f"sec\nlda {v2}\nsbc {v3}\nsta {v1}\n"
            f"lda {v2}+1\nsbc {v3}+1\nsta {v1}+1\n"
            f"lda {v2}+2\nsbc #0\nsta {v1}+2\n"
            f"lda {v2}+3\nsbc #0\nsta {v1}+3"
        )
        frags[f"vdu{loc}1=vdu{loc}1_minus_vwu{loc}2.asm"] = (
            f"sec\nlda {v1}\nsbc {v2}\nsta {v1}\n"
            f"lda {v1}+1\nsbc {v2}+1\nsta {v1}+1\n"
            f"bcs !+\ndec {v1}+2\nlda {v1}+2\ncmp #$ff\nbne !+\ndec {v1}+3\n!:"
        )

    # 11. Mixed 32-bit Dword vs 16-bit Word Comparisons (equality & ordering)
    for loc in ['z', 'm']:
        v1 = f"{{{loc}1}}"
        v2 = f"{{{loc}2}}"
        # vdu == vwu -> jump to la1
        frags[f"vdu{loc}1_eq_vwu{loc}2_then_la1.asm"] = (
            f"lda {v1}+3\nbne !+\n"
            f"lda {v1}+2\nbne !+\n"
            f"lda {v1}+1\ncmp {v2}+1\nbne !+\n"
            f"lda {v1}\ncmp {v2}\nbeq {{la1}}\n!:"
        )
        # vdu != vwu -> jump to la1
        frags[f"vdu{loc}1_neq_vwu{loc}2_then_la1.asm"] = (
            f"lda {v1}+3\nbne {{la1}}\n"
            f"lda {v1}+2\nbne {{la1}}\n"
            f"lda {v1}+1\ncmp {v2}+1\nbne {{la1}}\n"
            f"lda {v1}\ncmp {v2}\nbne {{la1}}"
        )
        # vdu >= vwu -> jump to la1
        frags[f"vdu{loc}1_ge_vwu{loc}2_then_la1.asm"] = (
            f"lda {v1}+3\nbne {{la1}}\n"
            f"lda {v1}+2\nbne {{la1}}\n"
            f"lda {v1}+1\ncmp {v2}+1\nbne !+\n"
            f"lda {v1}\ncmp {v2}\n!:\nbcs {{la1}}"
        )
        # vdu < vwu -> jump to la1
        frags[f"vdu{loc}1_lt_vwu{loc}2_then_la1.asm"] = (
            f"lda {v1}+3\nbne !+\n"
            f"lda {v1}+2\nbne !+\n"
            f"lda {v1}+1\ncmp {v2}+1\nbne !+\n"
            f"lda {v1}\ncmp {v2}\n!:\nbcc {{la1}}"
        )

    # 12. Dword composition to 24-bit Struct: vss = vwu dw= vwu
    for d_loc in ['z', 'm']:
        for s1_loc in ['z', 'm']:
            for s2_loc in ['z', 'm']:
                d = f"vss{d_loc}1"
                s1 = f"vwu{s1_loc}2"
                s2 = f"vwu{s2_loc}3"
                d_val = f"{{{d_loc}1}}"
                s1_val = f"{{{s1_loc}2}}"
                s2_val = f"{{{s2_loc}3}}"
                asm = (
                    f"lda {s1_val}\nsta {d_val}+2\n"
                    f"lda {s2_val}\nsta {d_val}\n"
                    f"lda {s2_val}+1\nsta {d_val}+1"
                )
                frags[f"{d}={s1}_dword_{s2}.asm"] = asm

                s1_s = f"vws{s1_loc}2"
                s2_s = f"vws{s2_loc}3"
                frags[f"{d}={s1_s}_dword_{s2_s}.asm"] = asm

    # 13. Dword composition to 32-bit Dword: vdu = vwu dw= vwu
    for d_loc in ['z', 'm']:
        for s1_loc in ['z', 'm']:
            for s2_loc in ['z', 'm']:
                d = f"vdu{d_loc}1"
                s1 = f"vwu{s1_loc}2"
                s2 = f"vwu{s2_loc}3"
                d_val = f"{{{d_loc}1}}"
                s1_val = f"{{{s1_loc}2}}"
                s2_val = f"{{{s2_loc}3}}"
                asm = (
                    f"lda {s1_val}\nsta {d_val}+2\n"
                    f"lda {s1_val}+1\nsta {d_val}+3\n"
                    f"lda {s2_val}\nsta {d_val}\n"
                    f"lda {s2_val}+1\nsta {d_val}+1"
                )
                frags[f"{d}={s1}_dword_{s2}.asm"] = asm

                d_s = f"vds{d_loc}1"
                s1_s = f"vws{s1_loc}2"
                s2_s = f"vws{s2_loc}3"
                frags[f"{d_s}={s1_s}_dword_{s2_s}.asm"] = asm

    total_created = 0
    total_updated = 0
    total_unchanged = 0

    print(f"Generating {len(frags)} fragments...")
    for name, asm in sorted(frags.items()):
        c, u, un = write_fragment(name, asm, dirs)
        if c > 0:
            print(f"  [NEW]     {name}")
            total_created += 1
        elif u > 0:
            print(f"  [UPDATED] {name}")
            total_updated += 1
        else:
            total_unchanged += 1

    print(f"\nResult: {total_created} new, {total_updated} updated, {total_unchanged} already synchronized.")
    print(f"All {len(frags)} orthogonal 24-bit/mixed-width fragments verified across source and runtime.")

if __name__ == '__main__':
    main()
