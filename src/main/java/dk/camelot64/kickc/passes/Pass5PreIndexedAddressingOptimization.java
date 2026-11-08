package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.Registers;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.model.values.ConstantValue;

import java.util.*;

/**
 * Assembler Peephole Optimization: Pre-Indexed Zero-Page Addressing (LDA/STA ($zp,X)).
 *
 * When accessing an array of pointers located in the Zero Page (e.g. __zp uint8_t* table[] or __address(0x20)),
 * KickC generates a synthetic 6-instruction dereference sequence via temporary Zero-Page registers $fe/$ff:
 *    LDA/LDY table,X
 *    STA.Z/STY.Z $fe
 *    LDA/LDY table+1,X
 *    STA.Z/STY.Z $ff
 *    LDY #0
 *    LDA/STA ($fe),Y
 *
 * On the 6502 CPU, Indexed Indirect addressing mode (zp,X) is a native single-cycle hardware instruction:
 *    LDA (table,X)  [opcode $A1, 6 cycles, 2 bytes]
 *    STA (table,X)  [opcode $81, 6 cycles, 2 bytes]
 *
 * This pass detects the synthetic 6-instruction pattern when the pointer array is located in the Zero Page,
 * and collapses it into a single hardware LDA/STA (table,X) instruction, saving 16-19 CPU cycles, 12 bytes of code,
 * and freeing up Zero-Page scratch registers $fe/$ff.
 */
public class Pass5PreIndexedAddressingOptimization extends Pass5AsmOptimization {

   private static class LineRef {
      final int index;
      final AsmChunk chunk;
      final AsmInstruction inst;

      LineRef(int index, AsmChunk chunk, AsmInstruction inst) {
         this.index = index;
         this.chunk = chunk;
         this.inst = inst;
      }
   }

   public Pass5PreIndexedAddressingOptimization(Program program) {
      super(program);
   }

   @Override
   public boolean optimize() {
      Set<String> zpSymbols = getZeroPageSymbols();
      boolean modified = false;

      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         List<AsmLine> lines = chunk.getLines();
         List<LineRef> instRefs = new ArrayList<>();
         for(int i = 0; i < lines.size(); i++) {
            AsmLine line = lines.get(i);
            if(line instanceof AsmInstruction) {
               instRefs.add(new LineRef(i, chunk, (AsmInstruction) line));
            }
         }

         if(instRefs.size() < 6) continue;

         for(int i = 0; i <= instRefs.size() - 6; i++) {
            LineRef r0 = instRefs.get(i);
            LineRef r1 = instRefs.get(i + 1);
            LineRef r2 = instRefs.get(i + 2);
            LineRef r3 = instRefs.get(i + 3);
            LineRef r4 = instRefs.get(i + 4);
            LineRef r5 = instRefs.get(i + 5);

            // Ensure no labels are between r0 and r5
            boolean hasInterveningLabel = false;
            for(int k = r0.index + 1; k < r5.index; k++) {
               if(lines.get(k) instanceof AsmLabel) {
                  hasInterveningLabel = true;
                  break;
               }
            }
            if(hasInterveningLabel) continue;

            String table = r0.inst.getOperand1();
            if(table == null) continue;
            table = table.trim();

            if(!isZeroPageSymbol(table, zpSymbols)) continue;

            // Check Pattern 1: Read (LDA)
            if(matchRead(r0.inst, r1.inst, r2.inst, r3.inst, r4.inst, r5.inst, table)) {
               CpuOpcode ldaIzx = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lda", CpuAddressingMode.IZX, false);
               if(ldaIzx != null) {
                  getLog().append("Optimizing pre-indexed zero-page pointer read: LDA (" + table + ",X)");
                  r0.inst.setCpuOpcode(ldaIzx);
                  r0.inst.setOperand1(table);
                  r0.inst.setOperand2(null);
                  r0.inst.setOperand3(null);

                  lines.remove(r5.inst);
                  lines.remove(r4.inst);
                  lines.remove(r3.inst);
                  lines.remove(r2.inst);
                  lines.remove(r1.inst);
                  modified = true;
                  break; // Re-scan chunk after modification
               }
            }

            // Check Pattern 2: Write (STA)
            if(matchWrite(r0.inst, r1.inst, r2.inst, r3.inst, r4.inst, r5.inst, table)) {
               CpuOpcode staIzx = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("sta", CpuAddressingMode.IZX, false);
               if(staIzx != null) {
                  getLog().append("Optimizing pre-indexed zero-page pointer write: STA (" + table + ",X)");
                  r0.inst.setCpuOpcode(staIzx);
                  r0.inst.setOperand1(table);
                  r0.inst.setOperand2(null);
                  r0.inst.setOperand3(null);

                  lines.remove(r5.inst);
                  lines.remove(r4.inst);
                  lines.remove(r3.inst);
                  lines.remove(r2.inst);
                  lines.remove(r1.inst);
                  modified = true;
                  break; // Re-scan chunk after modification
               }
            }
         }
      }

      return modified;
   }

   private boolean matchRead(AsmInstruction i0, AsmInstruction i1, AsmInstruction i2, AsmInstruction i3, AsmInstruction i4, AsmInstruction i5, String table) {
      String m0 = getMnemonic(i0);
      String m1 = getMnemonic(i1);
      String m2 = getMnemonic(i2);
      String m3 = getMnemonic(i3);
      String m4 = getMnemonic(i4);
      String m5 = getMnemonic(i5);

      if(!("lda".equals(m0) || "ldy".equals(m0))) return false;
      if(!isXIndexed(i0)) return false;

      if(!("sta".equals(m1) || "sty".equals(m1))) return false;
      if(!isZpFe(i1.getOperand1())) return false;

      if(!m0.equals(m2)) return false;
      if(!isXIndexed(i2)) return false;
      if(!isTablePlusOne(table, i2.getOperand1())) return false;

      if(!m1.equals(m3)) return false;
      if(!isZpFf(i3.getOperand1())) return false;

      if(!"ldy".equals(m4) || !isImmZero(i4.getOperand1())) return false;

      if(!"lda".equals(m5)) return false;
      if(!CpuAddressingMode.IZY.equals(i5.getCpuOpcode().getAddressingMode())) return false;
      return isZpFe(i5.getOperand1());
   }

   private boolean matchWrite(AsmInstruction i0, AsmInstruction i1, AsmInstruction i2, AsmInstruction i3, AsmInstruction i4, AsmInstruction i5, String table) {
      String m0 = getMnemonic(i0);
      String m1 = getMnemonic(i1);
      String m2 = getMnemonic(i2);
      String m3 = getMnemonic(i3);
      String m4 = getMnemonic(i4);
      String m5 = getMnemonic(i5);

      if(!"ldy".equals(m0)) return false;
      if(!isXIndexed(i0)) return false;

      if(!"sty".equals(m1)) return false;
      if(!isZpFe(i1.getOperand1())) return false;

      if(!"ldy".equals(m2)) return false;
      if(!isXIndexed(i2)) return false;
      if(!isTablePlusOne(table, i2.getOperand1())) return false;

      if(!"sty".equals(m3)) return false;
      if(!isZpFf(i3.getOperand1())) return false;

      if(!"ldy".equals(m4) || !isImmZero(i4.getOperand1())) return false;

      if(!"sta".equals(m5)) return false;
      if(!CpuAddressingMode.IZY.equals(i5.getCpuOpcode().getAddressingMode())) return false;
      return isZpFe(i5.getOperand1());
   }

   private static boolean isXIndexed(AsmInstruction inst) {
      if(inst.getCpuOpcode() == null) return false;
      CpuAddressingMode mode = inst.getCpuOpcode().getAddressingMode();
      return CpuAddressingMode.ABX.equals(mode) || CpuAddressingMode.ZPX.equals(mode);
   }

   private static String getMnemonic(AsmInstruction inst) {
      if(inst == null || inst.getCpuOpcode() == null) return "";
      return inst.getCpuOpcode().getMnemonic().toLowerCase();
   }

   private static boolean isTablePlusOne(String table, String op2) {
      if(table == null || op2 == null) return false;
      String t0 = table.trim();
      String t2 = op2.trim().replaceAll("\\s+", "");
      return t2.equals(t0 + "+1") || t2.equals(t0 + "+SIZEOF_CHAR") || t2.equals(t0 + "+$1");
   }

   private static boolean isZpFe(String op) {
      if(op == null) return false;
      String s = op.trim().toLowerCase();
      if(s.startsWith(".z ")) s = s.substring(3).trim();
      return s.equals("$fe") || s.equals("fe") || s.equals("254") || s.equals("$00fe") || s.equals("0xfe");
   }

   private static boolean isZpFf(String op) {
      if(op == null) return false;
      String s = op.trim().toLowerCase();
      if(s.startsWith(".z ")) s = s.substring(3).trim();
      return s.equals("$ff") || s.equals("ff") || s.equals("255") || s.equals("$00ff") || s.equals("0xff");
   }

   private static boolean isImmZero(String op) {
      if(op == null) return false;
      String s = op.trim();
      if(s.startsWith("#")) s = s.substring(1).trim();
      return s.equals("0") || s.equals("$0") || s.equals("$00") || s.equals("0x0") || s.equals("0x00");
   }

   private Set<String> getZeroPageSymbols() {
      Set<String> zpSymbols = new HashSet<>();
      for(Variable var : getProgram().getScope().getAllVariables(true)) {
         if(Variable.MemoryArea.ZEROPAGE_MEMORY.equals(var.getMemoryArea())) {
            addVarAliases(zpSymbols, var);
         } else if(var.getMemoryAddress() != null) {
            ConstantValue addr = var.getMemoryAddress();
            if(addr instanceof ConstantInteger && ((ConstantInteger) addr).getValue() < 256) {
               addVarAliases(zpSymbols, var);
            }
         } else if(var.getAllocation() instanceof Registers.RegisterZpMem) {
            addVarAliases(zpSymbols, var);
         }
      }

      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         for(AsmLine line : chunk.getLines()) {
            if(line instanceof AsmSetPc) {
               AsmSetPc setPc = (AsmSetPc) line;
               if(isAddressUnder256(setPc.getAddress())) {
                  zpSymbols.add(setPc.getName());
               }
            } else if(line instanceof AsmLabelDecl) {
               AsmLabelDecl decl = (AsmLabelDecl) line;
               if(isAddressUnder256(decl.getValue())) {
                  zpSymbols.add(decl.getName());
                  int dot = decl.getName().lastIndexOf('.');
                  if(dot >= 0) zpSymbols.add(decl.getName().substring(dot + 1));
               }
            }
         }
      }
      return zpSymbols;
   }

   private static void addVarAliases(Set<String> set, Variable var) {
      if(var.getLocalName() != null) set.add(var.getLocalName());
      if(var.getFullName() != null) set.add(var.getFullName());
   }

   private static boolean isZeroPageSymbol(String name, Set<String> zpSymbols) {
      if(name == null) return false;
      String n = name.trim();
      if(zpSymbols.contains(n)) return true;
      int dot = n.lastIndexOf('.');
      if(dot >= 0 && zpSymbols.contains(n.substring(dot + 1))) return true;
      return isAddressUnder256(n);
   }

   private static boolean isAddressUnder256(String addrStr) {
      if(addrStr == null) return false;
      String s = addrStr.trim();
      if(s.startsWith(".z ")) s = s.substring(3).trim();
      try {
         long val;
         if(s.startsWith("$")) val = Long.parseLong(s.substring(1), 16);
         else if(s.startsWith("0x") || s.startsWith("0X")) val = Long.parseLong(s.substring(2), 16);
         else val = Long.parseLong(s);
         return val >= 0 && val < 256;
      } catch(Exception e) {
         return false;
      }
   }
}
