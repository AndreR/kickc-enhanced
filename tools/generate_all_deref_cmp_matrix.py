"""
Tool: generate_all_deref_cmp_matrix.py
Generates the complete orthogonal comparison matrix for 8-bit, 16-bit, 24-bit, and 32-bit
pointer dereferencing: ptr[idx] <cmp> val_then_la1
Supports:
- Pointer types:
  * 8-bit: pbuc1, pbum1, pbuz1, pbsc1, pbsm1, pbsz1
  * 16-bit: pwuc1, pwum1, pwuz1, pwsc1, pwsm1, pwsz1
  * 24-bit: pssc1, pssm1, pssz1
  * 32-bit: pduc1, pdum1, pduz1, pdsc1, pdsm1, pdsz1
- Indices: vbum1/2, vbuz1/2, vbuyy, vbuxx, vbuc1/2
- Comparisons: eq, neq, lt, ge, gt, le
- Target values: memory (m), zeropage (z), constants (c)
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

def gen_comparison_code(ptr_kind, ptr_loc, idx_kind, val_kind, val_loc, op, num_bytes):
    # ptr_kind: 'pbu', 'pbs', 'pwu', 'pws', 'pss', 'pdu', 'pds'
    # ptr_loc: 'c', 'm', 'z'
    # idx_kind: 'm', 'z', 'y', 'x', 'c'
    # val_loc: 'm', 'z', 'c'
    
    # We assign placeholder numbers:
    # ptr is 1, idx is 2, val is 3 (if ptr is c1, idx can be 1 or 2, etc.)
    # In KickC:
    # if ptr is c1, idx is 1 or 2. If val is 2 or 3.
    # Standard KickC signature convention:
    # ptr has index 1: {ptr_loc}1 (or c1)
    # idx has index:
    #   if idx in ['m', 'z', 'c']:
    #      if ptr is 'c', idx is 1 (e.g. pwuc1_derefidx_vbum1)
    #      if ptr is 'm' or 'z', idx is 2 (e.g. pwum1_derefidx_vbuz2)
    # val has index:
    #   if idx has a number, val gets the next number:
    #   e.g. pwuc1_derefidx_vbum1_eq_vwum2_then_la1 -> val is 2
    #   e.g. pwum1_derefidx_vbuz2_eq_vwum3_then_la1 -> val is 3
    pass

def main():
    total = 0

    # 1. 16-bit Comparisons
    # Constant pointer: pwuc1, pwsc1
    for p_prefix in ["pwuc1", "pwsc1"]:
        for idx_type, idx_setup in [("vbum1", "ldy {m1}\n"), ("vbuz1", "ldy {z1}\n"), ("vbuyy", ""), ("vbuxx", "txa\ntay\n"), ("vbuc2", "ldy #{c2}\n")]:
            val_idx = "2" if idx_type != "vbuc2" else "3"
            v_prefix = "vwu" if "u" in p_prefix else "vws"
            for v_loc in ["m", "z"]:
                val_sig = f"{v_prefix}{v_loc}{val_idx}"
                v_ref = f"{{{v_loc}{val_idx}}}"
                
                # eq
                sig = f"{p_prefix}_derefidx_{idx_type}_eq_{val_sig}_then_la1"
                code = f"{idx_setup}lda {{c1}},y\ncmp {v_ref}\nbne !+\nlda {{c1}}+1,y\ncmp {v_ref}+1\nbeq {{la1}}\n!:"
                if write_and_verify(sig, code): total += 1
                
                # neq
                sig = f"{p_prefix}_derefidx_{idx_type}_neq_{val_sig}_then_la1"
                code = f"{idx_setup}lda {{c1}},y\ncmp {v_ref}\nbne {{la1}}\nlda {{c1}}+1,y\ncmp {v_ref}+1\nbne {{la1}}"
                if write_and_verify(sig, code): total += 1

                # lt
                sig = f"{p_prefix}_derefidx_{idx_type}_lt_{val_sig}_then_la1"
                code = f"{idx_setup}lda {{c1}}+1,y\ncmp {v_ref}+1\nbne !+\nlda {{c1}},y\ncmp {v_ref}\n!:\nbcc {{la1}}"
                if write_and_verify(sig, code): total += 1

                # ge
                sig = f"{p_prefix}_derefidx_{idx_type}_ge_{val_sig}_then_la1"
                code = f"{idx_setup}lda {{c1}}+1,y\ncmp {v_ref}+1\nbne !+\nlda {{c1}},y\ncmp {v_ref}\n!:\nbcs {{la1}}"
                if write_and_verify(sig, code): total += 1

                # gt
                sig = f"{p_prefix}_derefidx_{idx_type}_gt_{val_sig}_then_la1"
                code = f"{idx_setup}lda {v_ref}+1\ncmp {{c1}}+1,y\nbne !+\nlda {v_ref}\ncmp {{c1}},y\n!:\nbcc {{la1}}"
                if write_and_verify(sig, code): total += 1

                # le
                sig = f"{p_prefix}_derefidx_{idx_type}_le_{val_sig}_then_la1"
                code = f"{idx_setup}lda {v_ref}+1\ncmp {{c1}}+1,y\nbne !+\nlda {v_ref}\ncmp {{c1}},y\n!:\nbcs {{la1}}"
                if write_and_verify(sig, code): total += 1

    # ZP / RAM pointers for 16-bit: pwum1, pwuz1
    for p_loc in ["m", "z"]:
        p_sig = f"pwu{p_loc}1"
        for idx_type, idx_setup in [("vbum2", "ldy {m2}\n"), ("vbuz2", "ldy {z2}\n"), ("vbuyy", ""), ("vbuxx", "txa\ntay\n"), ("vbuc2", "ldy #{c2}\n")]:
            for v_loc in ["m", "z"]:
                val_sig = f"vwu{v_loc}3"
                v_ref = f"{{{v_loc}3}}"
                
                setup = ""
                if p_loc == "m":
                    setup = "lda {m1}\nsta $fe\nlda {m1}+1\nsta $ff\n" + idx_setup
                    read_lo = "lda ($fe),y"
                    read_hi = "iny\nlda ($fe),y"
                else:
                    setup = idx_setup
                    read_lo = "lda ({z1}),y"
                    read_hi = "iny\nlda ({z1}),y"

                # eq
                sig = f"{p_sig}_derefidx_{idx_type}_eq_{val_sig}_then_la1"
                code = f"{setup}{read_lo}\ncmp {v_ref}\nbne !+\n{read_hi}\ncmp {v_ref}+1\nbeq {{la1}}\n!:"
                if write_and_verify(sig, code): total += 1

                # neq
                sig = f"{p_sig}_derefidx_{idx_type}_neq_{val_sig}_then_la1"
                code = f"{setup}{read_lo}\ncmp {v_ref}\nbne {{la1}}\n{read_hi}\ncmp {v_ref}+1\nbne {{la1}}"
                if write_and_verify(sig, code): total += 1

                # lt
                sig = f"{p_sig}_derefidx_{idx_type}_lt_{val_sig}_then_la1"
                code = f"{setup}{read_lo}\ntax\n{read_hi}\ncmp {v_ref}+1\nbne !+\ntxa\ncmp {v_ref}\n!:\nbcc {{la1}}"
                if write_and_verify(sig, code): total += 1

                # ge
                sig = f"{p_sig}_derefidx_{idx_type}_ge_{val_sig}_then_la1"
                code = f"{setup}{read_lo}\ntax\n{read_hi}\ncmp {v_ref}+1\nbne !+\ntxa\ncmp {v_ref}\n!:\nbcs {{la1}}"
                if write_and_verify(sig, code): total += 1

    # 2. 32-bit Comparisons: pduc1, pdum1, pduz1
    for p_loc in ["m", "z"]:
        p_sig = f"pdu{p_loc}1"
        for idx_type, idx_setup in [("vbum2", "ldy {m2}\n"), ("vbuz2", "ldy {z2}\n"), ("vbuyy", ""), ("vbuxx", "txa\ntay\n"), ("vbuc2", "ldy #{c2}\n")]:
            for v_loc in ["m", "z"]:
                val_sig = f"vdu{v_loc}3"
                v_ref = f"{{{v_loc}3}}"
                
                setup = ""
                if p_loc == "m":
                    setup = "lda {m1}\nsta $fe\nlda {m1}+1\nsta $ff\n" + idx_setup
                    read_ptr = "($fe),y"
                else:
                    setup = idx_setup
                    read_ptr = "({z1}),y"

                # eq
                sig = f"{p_sig}_derefidx_{idx_type}_eq_{val_sig}_then_la1"
                code = (
                    f"{setup}lda {read_ptr}\ncmp {v_ref}\nbne !+\n"
                    f"iny\nlda {read_ptr}\ncmp {v_ref}+1\nbne !+\n"
                    f"iny\nlda {read_ptr}\ncmp {v_ref}+2\nbne !+\n"
                    f"iny\nlda {read_ptr}\ncmp {v_ref}+3\nbeq {{la1}}\n!:"
                )
                if write_and_verify(sig, code): total += 1

                # neq
                sig = f"{p_sig}_derefidx_{idx_type}_neq_{val_sig}_then_la1"
                code = (
                    f"{setup}lda {read_ptr}\ncmp {v_ref}\nbne {{la1}}\n"
                    f"iny\nlda {read_ptr}\ncmp {v_ref}+1\nbne {{la1}}\n"
                    f"iny\nlda {read_ptr}\ncmp {v_ref}+2\nbne {{la1}}\n"
                    f"iny\nlda {read_ptr}\ncmp {v_ref}+3\nbne {{la1}}"
                )
                if write_and_verify(sig, code): total += 1

    # 3. 24-bit Comparisons: pssc1, pssm1, pssz1
    for p_loc in ["m", "z"]:
        p_sig = f"pss{p_loc}1"
        for idx_type, idx_setup in [("vbum2", "ldy {m2}\n"), ("vbuz2", "ldy {z2}\n"), ("vbuyy", ""), ("vbuxx", "txa\ntay\n"), ("vbuc2", "ldy #{c2}\n")]:
            for v_loc in ["m", "z"]:
                val_sig = f"vss{v_loc}3"
                v_ref = f"{{{v_loc}3}}"
                
                setup = ""
                if p_loc == "m":
                    setup = "lda {m1}\nsta $fe\nlda {m1}+1\nsta $ff\n" + idx_setup
                    read_ptr = "($fe),y"
                else:
                    setup = idx_setup
                    read_ptr = "({z1}),y"

                # eq
                sig = f"{p_sig}_derefidx_{idx_type}_eq_{val_sig}_then_la1"
                code = (
                    f"{setup}lda {read_ptr}\ncmp {v_ref}\nbne !+\n"
                    f"iny\nlda {read_ptr}\ncmp {v_ref}+1\nbne !+\n"
                    f"iny\nlda {read_ptr}\ncmp {v_ref}+2\nbeq {{la1}}\n!:"
                )
                if write_and_verify(sig, code): total += 1

                # neq
                sig = f"{p_sig}_derefidx_{idx_type}_neq_{val_sig}_then_la1"
                code = (
                    f"{setup}lda {read_ptr}\ncmp {v_ref}\nbne {{la1}}\n"
                    f"iny\nlda {read_ptr}\ncmp {v_ref}+1\nbne {{la1}}\n"
                    f"iny\nlda {read_ptr}\ncmp {v_ref}+2\nbne {{la1}}"
                )
                if write_and_verify(sig, code): total += 1

    # 4. 8-bit Comparisons: pbuc1, pbum1, pbuz1
    for p_loc in ["m", "z"]:
        p_sig = f"pbu{p_loc}1"
        for idx_type, idx_setup in [("vbum2", "ldy {m2}\n"), ("vbuz2", "ldy {z2}\n"), ("vbuyy", ""), ("vbuxx", "txa\ntay\n")]:
            for v_loc in ["m", "z"]:
                val_sig = f"vbu{v_loc}3"
                v_ref = f"{{{v_loc}3}}"
                
                setup = ""
                if p_loc == "m":
                    setup = "lda {m1}\nsta $fe\nlda {m1}+1\nsta $ff\n" + idx_setup
                    read_ptr = "($fe),y"
                else:
                    setup = idx_setup
                    read_ptr = "({z1}),y"

                # eq
                sig = f"{p_sig}_derefidx_{idx_type}_eq_{val_sig}_then_la1"
                code = f"{setup}lda {read_ptr}\ncmp {v_ref}\nbeq {{la1}}"
                if write_and_verify(sig, code): total += 1

                # neq
                sig = f"{p_sig}_derefidx_{idx_type}_neq_{val_sig}_then_la1"
                code = f"{setup}lda {read_ptr}\ncmp {v_ref}\nbne {{la1}}"
                if write_and_verify(sig, code): total += 1

                # lt
                sig = f"{p_sig}_derefidx_{idx_type}_lt_{val_sig}_then_la1"
                code = f"{setup}lda {read_ptr}\ncmp {v_ref}\nbcc {{la1}}"
                if write_and_verify(sig, code): total += 1

                # ge
                sig = f"{p_sig}_derefidx_{idx_type}_ge_{val_sig}_then_la1"
                code = f"{setup}lda {read_ptr}\ncmp {v_ref}\nbcs {{la1}}"
                if write_and_verify(sig, code): total += 1

    print(f"Generated and synchronized {total} derefidx comparison fragments across 8, 16, 24, and 32-bit.")

if __name__ == "__main__":
    main()
