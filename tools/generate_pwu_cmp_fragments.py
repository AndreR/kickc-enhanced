"""
Tool: generate_pwu_cmp_fragments.py
Generates orthogonal comparison fragments for constant word pointers:
pwuc1_derefidx_<idx>_<cmp>_<val>_then_la1
"""

import os

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
    total = 0

    idx_configs = [
        ("vbum1", "ldy {m1}\n"),
        ("vbuz1", "ldy {z1}\n"),
        ("vbuyy", ""),
        ("vbuxx", "txa\ntay\n"),
    ]

    val_configs = [
        ("vwum2", "{m2}"),
        ("vwuz2", "{z2}"),
    ]

    for idx_name, idx_setup in idx_configs:
        for val_name, val_ref in val_configs:
            # eq
            sig = f"pwuc1_derefidx_{idx_name}_eq_{val_name}_then_la1"
            code = f"{idx_setup}lda {{c1}},y\ncmp {val_ref}\nbne !+\nlda {{c1}}+1,y\ncmp {val_ref}+1\nbeq {{la1}}\n!:"
            if write_and_verify(sig, code): total += 1

            # neq
            sig = f"pwuc1_derefidx_{idx_name}_neq_{val_name}_then_la1"
            code = f"{idx_setup}lda {{c1}},y\ncmp {val_ref}\nbne {{la1}}\nlda {{c1}}+1,y\ncmp {val_ref}+1\nbne {{la1}}"
            if write_and_verify(sig, code): total += 1

            # lt
            sig = f"pwuc1_derefidx_{idx_name}_lt_{val_name}_then_la1"
            code = f"{idx_setup}lda {{c1}}+1,y\ncmp {val_ref}+1\nbne !+\nlda {{c1}},y\ncmp {val_ref}\n!:\nbcc {{la1}}"
            if write_and_verify(sig, code): total += 1

            # ge
            sig = f"pwuc1_derefidx_{idx_name}_ge_{val_name}_then_la1"
            code = f"{idx_setup}lda {{c1}}+1,y\ncmp {val_ref}+1\nbne !+\nlda {{c1}},y\ncmp {val_ref}\n!:\nbcs {{la1}}"
            if write_and_verify(sig, code): total += 1

            # gt
            sig = f"pwuc1_derefidx_{idx_name}_gt_{val_name}_then_la1"
            code = f"{idx_setup}lda {val_ref}+1\ncmp {{c1}}+1,y\nbne !+\nlda {val_ref}\ncmp {{c1}},y\n!:\nbcc {{la1}}"
            if write_and_verify(sig, code): total += 1

            # le
            sig = f"pwuc1_derefidx_{idx_name}_le_{val_name}_then_la1"
            code = f"{idx_setup}lda {val_ref}+1\ncmp {{c1}}+1,y\nbne !+\nlda {val_ref}\ncmp {{c1}},y\n!:\nbcs {{la1}}"
            if write_and_verify(sig, code): total += 1

    print(f"Generated and synchronized {total} pwuc1 comparison fragments.")

if __name__ == "__main__":
    main()
