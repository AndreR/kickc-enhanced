import os

TARGET_DIRS = [
    r"C:\C64\KickC\fragment\mos6502-common",
    r"C:\C64\kickc-src\src\main\fragment\mos6502-common"
]

def emit(base_dir, name, code):
    path = os.path.join(base_dir, f"{name}.asm")
    with open(path, "w", encoding="ascii") as f:
        f.write(code.strip() + "\n")

count = 0

for base in TARGET_DIRS:
    os.makedirs(base, exist_ok=True)

    # 1. Tribyte = Dword (truncation: low 24-bits)
    # Dst: vtuz1, vtum1, vtsz1, vtsm1
    # Src: vduz2, vdum2, vdsz2, vdsm2, vduz1, vdum1, vdsz1, vdsm1
    tribyte_dsts = [
        ("vtuz1", "z1"), ("vtum1", "m1"),
        ("vtsz1", "z1"), ("vtsm1", "m1"),
    ]
    dword_srcs = [
        ("vduz2", "z2"), ("vdum2", "m2"),
        ("vdsz2", "z2"), ("vdsm2", "m2"),
        ("vduz1", "z1"), ("vdum1", "m1"),
        ("vdsz1", "z1"), ("vdsm1", "m1"),
    ]

    for t_name, t_var in tribyte_dsts:
        for d_name, d_var in dword_srcs:
            if t_var == d_var:
                continue
            src0 = "{" + d_var + "}"
            src1 = "{" + d_var + "}+1"
            src2 = "{" + d_var + "}+2"

            dst0 = "{" + t_var + "}"
            dst1 = "{" + t_var + "}+1"
            dst2 = "{" + t_var + "}+2"

            code = f"lda {src0}\nsta {dst0}\nlda {src1}\nsta {dst1}\nlda {src2}\nsta {dst2}"

            emit(base, f"{t_name}={d_name}", code)
            emit(base, f"{t_name}=_tribyte_{d_name}", code)
            emit(base, f"{t_name}=_stribyte_{d_name}", code)
            count += 3

    # 2. Dword = Tribyte (extension: low 24-bits + sign or zero extension of byte 3)
    # Dst: vduz1, vdum1, vdsz1, vdsm1
    # Src: vtuz2, vtum2, vtsz2, vtsm2
    tribyte_srcs = [
        ("vtuz2", "z2", False), ("vtum2", "m2", False),
        ("vtsz2", "z2", True),  ("vtsm2", "m2", True),
    ]
    dword_dsts = [
        ("vduz1", "z1"), ("vdum1", "m1"),
        ("vdsz1", "z1"), ("vdsm1", "m1"),
    ]

    for d_name, d_var in dword_dsts:
        for t_name, t_var, is_signed in tribyte_srcs:
            if d_var == t_var:
                continue
            src0 = "{" + t_var + "}"
            src1 = "{" + t_var + "}+1"
            src2 = "{" + t_var + "}+2"

            dst0 = "{" + d_var + "}"
            dst1 = "{" + d_var + "}+1"
            dst2 = "{" + d_var + "}+2"
            dst3 = "{" + d_var + "}+3"

            if not is_signed:
                code = f"lda {src0}\nsta {dst0}\nlda {src1}\nsta {dst1}\nlda {src2}\nsta {dst2}\nlda #0\nsta {dst3}"
            else:
                code = f"lda {src0}\nsta {dst0}\nlda {src1}\nsta {dst1}\nlda {src2}\nsta {dst2}\nora #$7f\nbmi !+\nlda #0\n!:\nsta {dst3}"

            emit(base, f"{d_name}={t_name}", code)
            emit(base, f"{d_name}=_dword_{t_name}", code)
            emit(base, f"{d_name}=_sdword_{t_name}", code)
            count += 3

print(f"Generated {count} tribyte/dword conversion fragments.")
