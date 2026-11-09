"""
Generates orthogonal byte-truncation and byte-arithmetic fragments for 6502/6510.
Covers truncated words (_byte_vwu, _byte_vws) and byte variables (vbu, vbs) in memory and zeropage.
"""

import os

RUN_DIR = r"C:\C64\KickC\fragment\mos6502-common"
SRC_DIR = r"C:\C64\kickc-src\src\main\fragment\mos6502-common"

def get_placeholder(reg_str):
    # Extracts placeholder like {m2} from _byte_vwum2 or vbum2
    if "m" in reg_str:
        num = "".join(c for c in reg_str if c.isdigit())
        return f"{{m{num}}}"
    elif "z" in reg_str:
        num = "".join(c for c in reg_str if c.isdigit())
        return f"{{z{num}}}"
    raise ValueError(f"Unknown register format: {reg_str}")

def main():
    os.makedirs(RUN_DIR, exist_ok=True)
    os.makedirs(SRC_DIR, exist_ok=True)

    created_count = 0

    operations = [
        ("minus", "sec\nlda {op1}\nsbc {op2}"),
        ("plus", "clc\nlda {op1}\nadc {op2}"),
        ("band", "lda {op1}\nand {op2}"),
        ("bor", "lda {op1}\nora {op2}"),
        ("bxor", "lda {op1}\neor {op2}"),
    ]

    destinations = [
        # (dest_reg, is_acc, op1_idx, op2_idx, sta_line)
        ("vbuaa", True, 1, 2, ""),
        ("vbsaa", True, 1, 2, ""),
        ("vbuz1", False, 2, 3, "\nsta {z1}"),
        ("vbsz1", False, 2, 3, "\nsta {z1}"),
        ("vbum1", False, 2, 3, "\nsta {m1}"),
        ("vbsm1", False, 2, 3, "\nsta {m1}"),
    ]

    op1_types = [
        "_byte_vwum", "_byte_vwsm", "_byte_vwuz", "_byte_vwsz",
        "vbum", "vbsm", "vbuz", "vbsz"
    ]

    op2_types = [
        "_byte_vwum", "_byte_vwsm", "_byte_vwuz", "_byte_vwsz",
        "vbum", "vbsm", "vbuz", "vbsz"
    ]

    for op_name, op_asm_template in operations:
        for dest, is_acc, idx1, idx2, sta_code in destinations:
            for t1 in op1_types:
                # filter sign mismatch if needed or allow both
                for t2 in op2_types:
                    # skip if both are pure registers/zeropage and already common, but let's check existence
                    sig1 = f"{t1}{idx1}"
                    sig2 = f"{t2}{idx2}"
                    frag_name = f"{dest}={sig1}_{op_name}_{sig2}.asm"

                    run_path = os.path.join(RUN_DIR, frag_name)
                    src_path = os.path.join(SRC_DIR, frag_name)

                    if os.path.exists(run_path) and os.path.exists(src_path):
                        continue

                    p1 = get_placeholder(sig1)
                    p2 = get_placeholder(sig2)

                    body = op_asm_template.format(op1=p1, op2=p2) + sta_code + "\n"

                    # Normalize newlines
                    body = body.replace("\r\n", "\n")

                    with open(run_path, "w", encoding="utf-8", newline="\n") as f:
                        f.write(body)
                    with open(src_path, "w", encoding="utf-8", newline="\n") as f:
                        f.write(body)

                    created_count += 1

    print(f"Generated {created_count} missing orthogonal byte fragments.")

if __name__ == "__main__":
    main()
