"""
Tool: generate_ptr_copy_fragments.py
Generates orthogonal byte pointer-to-pointer copy fragments: ptr1[idx1] = ptr2[idx2]
Supports combinations of memory pointers (pbum) and zeropage pointers (pbuz)
with all index types: vbuz, vbum, vbuxx, vbuyy, vbuc.
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

    idx1_configs = [
        # (name, setup_pbum, setup_pbuz)
        ("vbuz2", "ldy {z2}\nsta ($fe),y", "ldy {z2}\nsta ({z1}),y"),
        ("vbum2", "ldy {m2}\nsta ($fe),y", "ldy {m2}\nsta ({z1}),y"),
        ("vbuc2", "ldy #{c2}\nsta ($fe),y", "ldy #{c2}\nsta ({z1}),y"),
        ("vbuxx", "pha\ntxa\ntay\npla\nsta ($fe),y", "pha\ntxa\ntay\npla\nsta ({z1}),y"),
    ]

    idx2_configs = [
        # (name, read_pbum, read_pbuz)
        ("vbuz4", "ldy {z4}\nlda ($fe),y", "ldy {z4}\nlda ({z3}),y"),
        ("vbum4", "ldy {m4}\nlda ($fe),y", "ldy {m4}\nlda ({z3}),y"),
        ("vbuc4", "ldy #{c4}\nlda ($fe),y", "ldy #{c4}\nlda ({z3}),y"),
        ("vbuxx", "txa\ntay\nlda ($fe),y", "txa\ntay\nlda ({z3}),y"),
        ("vbuyy", "lda ($fe),y", "lda ({z3}),y"),
    ]

    for p1_type in ["pbum", "pbuz"]:
        for p2_type in ["pbum", "pbuz"]:
            for i1_name, i1_pbum, i1_pbuz in idx1_configs:
                for i2_name, i2_pbum, i2_pbuz in idx2_configs:
                    p1_sig = f"{p1_type}1"
                    p2_sig = f"{p2_type}3"

                    # Adjust numbering when an index doesn't take a number (like vbuxx/vbuyy)
                    # For example: pbum1_derefidx_vbum2 = pbum3_derefidx_vbuxx
                    sig = f"{p1_sig}_derefidx_{i1_name}={p2_sig}_derefidx_{i2_name}"

                    lines = []
                    # Read src (ptr 3)
                    if p2_type == "pbum":
                        lines.append("lda {m3}\nsta $fe\nlda {m3}+1\nsta $ff")
                        lines.append(i2_pbum)
                    else: # pbuz
                        lines.append(i2_pbuz)

                    # Write dst (ptr 1)
                    if p1_type == "pbum":
                        lines.append("ldy {m1}\nsty $fe\nldy {m1}+1\nsty $ff")
                        lines.append(i1_pbum)
                    else: # pbuz
                        lines.append(i1_pbuz)

                    code = "\n".join(lines)
                    if write_and_verify(sig, code):
                        total += 1

    print(f"Generated and synchronized {total} pointer copy fragments.")

if __name__ == "__main__":
    main()
