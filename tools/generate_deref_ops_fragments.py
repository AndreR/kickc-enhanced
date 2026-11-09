"""
Tool: generate_deref_ops_fragments.py
Generates orthogonal derefidx read, write, and arithmetic (+1, -1, +c, -c) fragments
for byte pointer indexing (pbum via $fe/$ff, and pbuz directly on ZP) in KickC.
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

    # 1. READS (Assignment)
    # 1.1 vbuaa = pbum1_derefidx_vbuc1
    write_and_verify("vbuaa=pbum1_derefidx_vbuc1", "lda {m1}\nsta $fe\nlda {m1}+1\nsta $ff\nldy #{c1}\nlda ($fe),y")
    write_and_verify("vbuaa=pbuz1_derefidx_vbuc1", "ldy #{c1}\nlda ({z1}),y")
    write_and_verify("vbuxx=pbum1_derefidx_vbuc1", "lda {m1}\nsta $fe\nlda {m1}+1\nsta $ff\nldy #{c1}\nlda ($fe),y\ntax")
    write_and_verify("vbuxx=pbuz1_derefidx_vbuc1", "ldy #{c1}\nlda ({z1}),y\ntax")
    write_and_verify("vbuyy=pbum1_derefidx_vbuc1", "lda {m1}\nsta $fe\nlda {m1}+1\nsta $ff\nldy #{c1}\nlda ($fe),y\ntay")
    write_and_verify("vbuyy=pbuz1_derefidx_vbuc1", "ldy #{c1}\nlda ({z1}),y\ntay")

    for dst_type, dst_reg, dst_sta in [("vbuz1", "{z1}", "sta {z1}"), ("vbum1", "{m1}", "sta {m1}")]:
        write_and_verify(f"{dst_type}=pbum2_derefidx_vbuc1", f"lda {{m2}}\nsta $fe\nlda {{m2}}+1\nsta $ff\nldy #{{c1}}\nlda ($fe),y\n{dst_sta}")
        write_and_verify(f"{dst_type}=pbuz2_derefidx_vbuc1", f"ldy #{{c1}}\nlda ({{z2}}),y\n{dst_sta}")
        write_and_verify(f"{dst_type}=pbum2_derefidx_vbuyy", f"lda {{m2}}\nsta $fe\nlda {{m2}}+1\nsta $ff\nlda ($fe),y\n{dst_sta}")
        write_and_verify(f"{dst_type}=pbuz2_derefidx_vbuyy", f"lda ({{z2}}),y\n{dst_sta}")

    # 2. STORES (Assignment to derefidx)
    # 2.1 pbum1_derefidx_vbuc1 = ...
    write_and_verify("pbum1_derefidx_vbuc1=vbuc2", "lda {m1}\nsta $fe\nlda {m1}+1\nsta $ff\nlda #{c2}\nldy #{c1}\nsta ($fe),y")
    write_and_verify("pbum1_derefidx_vbuc1=vbuz2", "lda {m1}\nsta $fe\nlda {m1}+1\nsta $ff\nlda {z2}\nldy #{c1}\nsta ($fe),y")
    write_and_verify("pbum1_derefidx_vbuc1=vbum2", "lda {m1}\nsta $fe\nlda {m1}+1\nsta $ff\nlda {m2}\nldy #{c1}\nsta ($fe),y")
    write_and_verify("pbum1_derefidx_vbuc1=vbuxx", "lda {m1}\nsta $fe\nlda {m1}+1\nsta $ff\ntxa\nldy #{c1}\nsta ($fe),y")
    write_and_verify("pbum1_derefidx_vbuc1=vbuyy", "lda {m1}\nsta $fe\nlda {m1}+1\nsta $ff\ntya\nldy #{c1}\nsta ($fe),y")

    # 2.2 pbuz1_derefidx_vbuc1 = ...
    write_and_verify("pbuz1_derefidx_vbuc1=vbuaa", "ldy #{c1}\nsta ({z1}),y")
    write_and_verify("pbuz1_derefidx_vbuc1=vbuc2", "lda #{c2}\nldy #{c1}\nsta ({z1}),y")
    write_and_verify("pbuz1_derefidx_vbuc1=vbuz2", "lda {z2}\nldy #{c1}\nsta ({z1}),y")
    write_and_verify("pbuz1_derefidx_vbuc1=vbum2", "lda {m2}\nldy #{c1}\nsta ({z1}),y")
    write_and_verify("pbuz1_derefidx_vbuc1=vbuxx", "txa\nldy #{c1}\nsta ({z1}),y")
    write_and_verify("pbuz1_derefidx_vbuc1=vbuyy", "tya\nldy #{c1}\nsta ({z1}),y")

    # 3. ARITHMETIC (+1, -1, +c, -c)
    arith_ops = [
        ("minus_1", "sec\nsbc #1"),
        ("plus_1", "clc\nadc #1"),
        ("minus_vbuc2", "sec\nsbc #{c2}"),
        ("plus_vbuc2", "clc\nadc #{c2}"),
    ]

    for op_name, op_asm in arith_ops:
        # Dest vbuaa
        write_and_verify(f"vbuaa=pbum1_derefidx_vbuc1_{op_name}", f"lda {{m1}}\nsta $fe\nlda {{m1}}+1\nsta $ff\nldy #{{c1}}\nlda ($fe),y\n{op_asm}")
        write_and_verify(f"vbuaa=pbuz1_derefidx_vbuc1_{op_name}", f"ldy #{{c1}}\nlda ({{z1}}),y\n{op_asm}")
        write_and_verify(f"vbuaa=pbum1_derefidx_vbuyy_{op_name}", f"lda {{m1}}\nsta $fe\nlda {{m1}}+1\nsta $ff\nlda ($fe),y\n{op_asm}")
        write_and_verify(f"vbuaa=pbuz1_derefidx_vbuyy_{op_name}", f"lda ({{z1}}),y\n{op_asm}")

        # Dest vbuz1 / vbum1
        for dst_type, dst_reg, dst_sta in [("vbuz1", "{z1}", "sta {z1}"), ("vbum1", "{m1}", "sta {m1}")]:
            write_and_verify(f"{dst_type}=pbum2_derefidx_vbuc1_{op_name}", f"lda {{m2}}\nsta $fe\nlda {{m2}}+1\nsta $ff\nldy #{{c1}}\nlda ($fe),y\n{op_asm}\n{dst_sta}")
            write_and_verify(f"{dst_type}=pbuz2_derefidx_vbuc1_{op_name}", f"ldy #{{c1}}\nlda ({{z2}}),y\n{op_asm}\n{dst_sta}")
            write_and_verify(f"{dst_type}=pbum2_derefidx_vbuyy_{op_name}", f"lda {{m2}}\nsta $fe\nlda {{m2}}+1\nsta $ff\nlda ($fe),y\n{op_asm}\n{dst_sta}")
            write_and_verify(f"{dst_type}=pbuz2_derefidx_vbuyy_{op_name}", f"lda ({{z2}}),y\n{op_asm}\n{dst_sta}")

    print("Generated and synchronized derefidx read/write/arithmetic fragments.")

if __name__ == "__main__":
    main()
