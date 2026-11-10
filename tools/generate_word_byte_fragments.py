import os

dirs = [
    r"C:\C64\KickC\fragment\mos6502-common",
    r"c:\C64\kickc-src\src\main\fragment\mos6502-common"
]

def write_frag(sig, asm):
    name = f"{sig}.asm"
    for d in dirs:
        p = os.path.join(d, name)
        if not os.path.exists(p):
            with open(p, "w", encoding="utf-8", newline="\n") as f:
                f.write(asm.strip() + "\n")

dsts = ["vbuz1", "vbum1", "vbuaa", "vbuxx", "vbuyy"]
words = [("vwuz1", "{z1}"), ("vwuz2", "{z2}"), ("vwum1", "{m1}"), ("vwum2", "{m2}")]
bytes_ops = [
    ("vbuaa", None),
    ("vbuxx", None),
    ("vbuyy", None),
    ("vbuz1", "{z1}"),
    ("vbuz2", "{z2}"),
    ("vbuz3", "{z3}"),
    ("vbum1", "{m1}"),
    ("vbum2", "{m2}"),
    ("vbuc1", "#{c1}")
]

count = 0
for dst in dsts:
    for w_name, w_ph in words:
        for b_name, b_ph in bytes_ops:
            # Skip if same index on z or m
            # 1. Plus: dst = word + byte
            sig1 = f"{dst}={w_name}_plus_{b_name}"
            # 2. Plus: dst = byte + word
            sig2 = f"{dst}={b_name}_plus_{w_name}"
            # 3. Minus: dst = word - byte
            sig3 = f"{dst}={w_name}_minus_{b_name}"
            # 4. Minus: dst = byte - word
            sig4 = f"{dst}={b_name}_minus_{w_name}"

            # Generate ASM for sig1 & sig2: word + byte
            # A = byte, adc word low
            lines = ["clc"]
            if b_name == "vbuaa":
                lines.append(f"adc {w_ph}")
            elif b_name == "vbuxx":
                lines.append("txa")
                lines.append(f"adc {w_ph}")
            elif b_name == "vbuyy":
                lines.append("tya")
                lines.append(f"adc {w_ph}")
            else:
                lines.append(f"lda {b_ph}")
                lines.append(f"adc {w_ph}")
            
            if dst == "vbuaa":
                pass
            elif dst == "vbuxx":
                lines.append("tax")
            elif dst == "vbuyy":
                lines.append("tay")
            elif dst == "vbuz1":
                lines.append("sta {z1}")
            elif dst == "vbum1":
                lines.append("sta {m1}")

            asm_plus = "\n".join(lines)
            write_frag(sig1, asm_plus)
            write_frag(sig2, asm_plus)
            count += 2

            # Generate ASM for sig3: word - byte
            lines = ["sec"]
            lines.append(f"lda {w_ph}")
            if b_name == "vbuaa":
                lines.append(f"sbc #0 ; or sbc reg") # but 6502 can't sbc A directly if word is loaded into A
                # actually: lda word; sec; sbc ... wait, if b is A:
                # eor #$ff; sec; adc word
            lines_m = ["sec"]
            if b_name == "vbuaa":
                lines_m = ["eor #$ff", "sec", f"adc {w_ph}"]
            elif b_name == "vbuxx":
                lines_m = [f"lda {w_ph}", "sec", "stx $fe", "sbc $fe"]
            elif b_name == "vbuyy":
                lines_m = [f"lda {w_ph}", "sec", "sty $fe", "sbc $fe"]
            else:
                lines_m = [f"lda {w_ph}", "sec", f"sbc {b_ph}"]

            if dst == "vbuaa":
                pass
            elif dst == "vbuxx":
                lines_m.append("tax")
            elif dst == "vbuyy":
                lines_m.append("tay")
            elif dst == "vbuz1":
                lines_m.append("sta {z1}")
            elif dst == "vbum1":
                lines_m.append("sta {m1}")
            write_frag(sig3, "\n".join(lines_m))
            count += 1

print(f"Generated {count} fragments.")
