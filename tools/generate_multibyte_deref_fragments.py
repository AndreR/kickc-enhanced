"""
Tool: generate_multibyte_deref_fragments.py
Generates orthogonal multi-byte pointer dereference loads and stores (16-bit words, 24-bit structs, 32-bit dwords).
Supports memory pointers (via $fe/$ff) and zeropage pointers, with constant (vbuc1) and register (vbuyy, vbuxx) indices.
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

    type_configs = [
        # (val_type, ptr_type, num_bytes)
        ("vwu", "pwu", 2),
        ("vws", "pws", 2),
        ("vss", "pss", 3),
        ("vdu", "pdu", 4),
        ("vds", "pds", 4),
    ]

    index_configs = [
        # (idx_name, idx_setup_code)
        ("vbuc1", "ldy #{c1}\n"),
        ("vbuyy", ""),
        ("vbuxx", "txa\ntay\n"),
    ]

    for val_prefix, ptr_prefix, num_bytes in type_configs:
        for idx_name, idx_setup in index_configs:
            # 1. LOADS: val_dst = ptr_src _derefidx_ idx
            for dst_loc in ['z', 'm']:
                for src_loc in ['z', 'm']:
                    dst_sig = f"{val_prefix}{dst_loc}1"
                    src_sig = f"{ptr_prefix}{src_loc}2"
                    sig = f"{dst_sig}={src_sig}_derefidx_{idx_name}"

                    dst_ref = f"{{{dst_loc}1}}"
                    lines = []
                    if src_loc == 'm':
                        lines.append(f"lda {{{src_loc}2}}\nsta $fe\nlda {{{src_loc}2}}+1\nsta $ff")
                        if idx_setup:
                            lines.append(idx_setup.strip())
                        for b in range(num_bytes):
                            if b > 0:
                                lines.append("iny")
                            offset_str = f"+{b}" if b > 0 else ""
                            lines.append(f"lda ($fe),y\nsta {dst_ref}{offset_str}")
                    else:
                        if idx_setup:
                            lines.append(idx_setup.strip())
                        for b in range(num_bytes):
                            if b > 0:
                                lines.append("iny")
                            offset_str = f"+{b}" if b > 0 else ""
                            lines.append(f"lda ({{{src_loc}2}}),y\nsta {dst_ref}{offset_str}")

                    code = "\n".join(lines)
                    if write_and_verify(sig, code):
                        total += 1

            # 2. STORES: ptr_dst _derefidx_ idx = val_src
            for dst_loc in ['z', 'm']:
                for src_loc in ['z', 'm']:
                    dst_sig = f"{ptr_prefix}{dst_loc}1"
                    src_sig = f"{val_prefix}{src_loc}2"
                    sig = f"{dst_sig}_derefidx_{idx_name}={src_sig}"

                    src_ref = f"{{{src_loc}2}}"
                    lines = []
                    if dst_loc == 'm':
                        lines.append(f"lda {{{dst_loc}1}}\nsta $fe\nlda {{{dst_loc}1}}+1\nsta $ff")
                        if idx_setup:
                            lines.append(idx_setup.strip())
                        for b in range(num_bytes):
                            if b > 0:
                                lines.append("iny")
                            offset_str = f"+{b}" if b > 0 else ""
                            lines.append(f"lda {src_ref}{offset_str}\nsta ($fe),y")
                    else:
                        if idx_setup:
                            lines.append(idx_setup.strip())
                        for b in range(num_bytes):
                            if b > 0:
                                lines.append("iny")
                            offset_str = f"+{b}" if b > 0 else ""
                            lines.append(f"lda {src_ref}{offset_str}\nsta ({{{dst_loc}1}}),y")

                    code = "\n".join(lines)
                    if write_and_verify(sig, code):
                        total += 1

                # 3. CONSTANT STORES: ptr_dst _derefidx_ idx = const_val
                # e.g. pwum1_derefidx_vbuc1 = vwuc2 or pduz1_derefidx_vbuyy = vduc1
                c_idx = "2" if idx_name == "vbuc1" else "1"
                c_val_sig = f"{val_prefix}c{c_idx}"
                sig_const = f"{ptr_prefix}{dst_loc}1_derefidx_{idx_name}={c_val_sig}"
                lines_c = []
                target_base = "($fe),y" if dst_loc == 'm' else f"({{{dst_loc}1}}),y"

                if dst_loc == 'm':
                    lines_c.append(f"lda {{{dst_loc}1}}\nsta $fe\nlda {{{dst_loc}1}}+1\nsta $ff")
                if idx_setup:
                    lines_c.append(idx_setup.strip())

                for b in range(num_bytes):
                    if b > 0:
                        lines_c.append("iny")
                    if b == 0:
                        lines_c.append(f"lda #<{{c{c_idx}}}")
                    elif b == 1:
                        lines_c.append(f"lda #>{{c{c_idx}}}")
                    elif b == 2:
                        lines_c.append(f"lda #<({{c{c_idx}}}>>16)")
                    elif b == 3:
                        lines_c.append(f"lda #>({{c{c_idx}}}>>16)")
                    lines_c.append(f"sta {target_base}")

                code_c = "\n".join(lines_c)
                if write_and_verify(sig_const, code_c):
                    total += 1

    print(f"Generated and synchronized {total} multibyte derefidx fragments.")

if __name__ == "__main__":
    main()
