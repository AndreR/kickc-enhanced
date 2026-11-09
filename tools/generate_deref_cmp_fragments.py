"""
Tool: generate_deref_cmp_fragments.py
Generates orthogonal derefidx comparison fragments for 6502 pointer indexing in KickC.
Covers pbum (memory pointers via $fe/$ff) and pbuz (zeropage pointers) with constant and register offsets.
"""

import os
import subprocess

DIRS = [
    r"C:\C64\KickC\fragment\mos6502-common",
    r"c:\C64\kickc-src\src\main\fragment\mos6502-common"
]

def write_and_verify(sig, code):
    created = False
    clean = code.strip() + "\n"
    for d in DIRS:
        os.makedirs(d, exist_ok=True)
        fp = os.path.join(d, f"{sig}.asm")
        if not os.path.exists(fp):
            with open(fp, "w", encoding="utf-8", newline="\n") as f:
                f.write(clean)
            created = True
        else:
            with open(fp, "r", encoding="utf-8", errors="ignore") as f:
                cur = f.read()
            if cur != clean:
                with open(fp, "w", encoding="utf-8", newline="\n") as f:
                    f.write(clean)
                created = True
    return created

def main():
    cmp_ops = [
        ("lt", "cmp {const}\nbcc {la1}"),
        ("ge", "cmp {const}\nbcs {la1}"),
        ("eq", "cmp {const}\nbeq {la1}"),
        ("neq", "cmp {const}\nbne {la1}"),
        ("gt", "cmp {const}\nbeq !+\nbcs {la1}\n!:"),
        ("le", "cmp {const}\nbcc {la1}\nbeq {la1}"),
    ]

    total = 0

    # 1. Constant index vbuc1 compared to constant vbuc2
    for op_name, op_asm in cmp_ops:
        # pbum1_derefidx_vbuc1_<op>_vbuc2_then_la1
        sig_pbum = f"pbum1_derefidx_vbuc1_{op_name}_vbuc2_then_la1"
        code_pbum = (
            "lda {m1}\nsta $fe\n"
            "lda {m1}+1\nsta $ff\n"
            "ldy #{c1}\n"
            "lda ($fe),y\n" +
            op_asm.format(const="#{c2}", la1="{la1}")
        )
        if write_and_verify(sig_pbum, code_pbum):
            total += 1

        # pbuz1_derefidx_vbuc1_<op>_vbuc2_then_la1
        sig_pbuz = f"pbuz1_derefidx_vbuc1_{op_name}_vbuc2_then_la1"
        code_pbuz = (
            "ldy #{c1}\n"
            "lda ({z1}),y\n" +
            op_asm.format(const="#{c2}", la1="{la1}")
        )
        if write_and_verify(sig_pbuz, code_pbuz):
            total += 1

    # 2. Register index vbuyy compared to constant vbuc1
    for op_name, op_asm in cmp_ops:
        sig_pbum = f"pbum1_derefidx_vbuyy_{op_name}_vbuc1_then_la1"
        code_pbum = (
            "lda {m1}\nsta $fe\n"
            "lda {m1}+1\nsta $ff\n"
            "lda ($fe),y\n" +
            op_asm.format(const="#{c1}", la1="{la1}")
        )
        if write_and_verify(sig_pbum, code_pbum):
            total += 1

        sig_pbuz = f"pbuz1_derefidx_vbuyy_{op_name}_vbuc1_then_la1"
        code_pbuz = (
            "lda ({z1}),y\n" +
            op_asm.format(const="#{c1}", la1="{la1}")
        )
        if write_and_verify(sig_pbuz, code_pbuz):
            total += 1

    # 3. Register index vbuxx compared to constant vbuc1
    for op_name, op_asm in cmp_ops:
        sig_pbum = f"pbum1_derefidx_vbuxx_{op_name}_vbuc1_then_la1"
        code_pbum = (
            "lda {m1}\nsta $fe\n"
            "lda {m1}+1\nsta $ff\n"
            "txa\ntay\n"
            "lda ($fe),y\n" +
            op_asm.format(const="#{c1}", la1="{la1}")
        )
        if write_and_verify(sig_pbum, code_pbum):
            total += 1

        sig_pbuz = f"pbuz1_derefidx_vbuxx_{op_name}_vbuc1_then_la1"
        code_pbuz = (
            "txa\ntay\n"
            "lda ({z1}),y\n" +
            op_asm.format(const="#{c1}", la1="{la1}")
        )
        if write_and_verify(sig_pbuz, code_pbuz):
            total += 1

    print(f"Generated and synchronized {total} derefidx comparison fragments.")

if __name__ == "__main__":
    main()
