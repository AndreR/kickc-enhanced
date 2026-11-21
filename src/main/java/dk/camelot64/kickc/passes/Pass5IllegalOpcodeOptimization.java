package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.TargetCpu;

import java.util.*;

/**
 * Assembler Peephole Optimization for stable NMOS 6510 Unintended (Illegal) Opcodes.
 * Combines pairs of standard instructions into single stable illegal opcodes
 * (e.g. LDA/TAX -> LAX, AND #imm; LSR -> ALR, DEC/CMP -> DCP, INC/SBC -> ISC, etc.).
 * Only executes when the target CPU supports MOS6502_UNODC (e.g. mos6502x, rom6502x).
 */
public class Pass5IllegalOpcodeOptimization extends Pass5AsmOptimization {

   public Pass5IllegalOpcodeOptimization(Program program) {
      super(program);
   }

   @Override
   public boolean optimize() {
      // Only execute if target CPU supports undocumented/illegal opcodes
      if(!getAsmProgram().getTargetCpu().getFeatures().contains(TargetCpu.Feature.MOS6502_UNODC)) {
         return false;
      }

      boolean overallModified = false;
      boolean passModified = true;

      while(passModified) {
         passModified = false;

         List<LineRef> allLines = flattenLines(getAsmProgram());
         Map<String, CanonicalResolver> scopeResolvers = buildScopeResolvers(allLines);
         Map<String, Map<String, String>> scopeAliasMap = new HashMap<>();
         for(LineRef ref : allLines) {
            if(ref.line instanceof AsmLabelDecl) {
               AsmLabelDecl decl = (AsmLabelDecl) ref.line;
               scopeAliasMap.computeIfAbsent(ref.scope, k -> new HashMap<>()).put(decl.getName(), decl.getValue());
            }
         }

         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref1 = allLines.get(i);
            String currentScope = ref1.scope;
            if(!(ref1.line instanceof AsmInstruction)) {
               continue;
            }
            AsmInstruction inst1 = (AsmInstruction) ref1.line;
            if(inst1.isNoOptimize() || inst1.getCpuOpcode() == null) {
               continue;
            }

            int nextIdx = findNextInstruction(allLines, i + 1);
            if(nextIdx == -1) {
               continue;
            }
            LineRef ref2 = allLines.get(nextIdx);
            AsmInstruction inst2 = (AsmInstruction) ref2.line;
            if(inst2.isNoOptimize() || inst2.getCpuOpcode() == null) {
               continue;
            }

            CpuOpcode op1 = inst1.getCpuOpcode();
            CpuOpcode op2 = inst2.getCpuOpcode();
            String mnem1 = op1.getMnemonic();
            String mnem2 = op2.getMnemonic();
            CpuAddressingMode mode1 = op1.getAddressingMode();
            CpuAddressingMode mode2 = op2.getAddressingMode();
            CanonicalResolver resolver = scopeResolvers.getOrDefault(currentScope, scopeResolvers.get(""));

            // -------------------------------------------------------------
            // Pattern 0j: Redundant TXA after LAX
            // -------------------------------------------------------------
            if(mnem1.equals("lax")) {
               if(mnem2.equals("txa")) {
                  getLog().append("Eliminating redundant TXA after LAX");
                  ref2.chunk.getLines().remove(inst2);
                  passModified = true;
                  overallModified = true;
                  break;
               } else if(mnem2.equals("sta") || mnem2.equals("stx")) {
                  int i3 = findNextInstruction(allLines, nextIdx + 1);
                  if(i3 != -1) {
                     AsmInstruction instB = (AsmInstruction) allLines.get(i3).line;
                     if(instB.getCpuOpcode() != null && instB.getCpuOpcode().getMnemonic().equals("txa")) {
                        getLog().append("Eliminating redundant TXA after LAX and store");
                        allLines.get(i3).chunk.getLines().remove(instB);
                        passModified = true;
                        overallModified = true;
                        break;
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 1: LDA <mem>; TAX -> LAX <mem>
            // Stable addressing modes: ZP, ABS, IZY, ABY, ZPY, IZX
            // (IMM is strictly excluded because LAX #imm is analog/unstable!)
            // -------------------------------------------------------------
            if(mnem1.equals("lda") && mnem2.equals("tax") && mode2.equals(CpuAddressingMode.NON)) {
               if(mode1.equals(CpuAddressingMode.ZP) ||
                  mode1.equals(CpuAddressingMode.ABS) ||
                  mode1.equals(CpuAddressingMode.IZY) ||
                  mode1.equals(CpuAddressingMode.ABY) ||
                  mode1.equals(CpuAddressingMode.ZPY) ||
                  mode1.equals(CpuAddressingMode.IZX)) {
                  CpuOpcode laxOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lax", mode1, false);
                  if(laxOpcode != null) {
                     getLog().append("Replacing " + inst1.getAsm() + "; " + inst2.getAsm() + " with LAX");
                     inst1.setCpuOpcode(laxOpcode);
                     ref2.chunk.getLines().remove(inst2);
                     passModified = true;
                     overallModified = true;
                     break;
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 1b: LDX <mem>; TXA -> LAX <mem>
            // -------------------------------------------------------------
            if(mnem1.equals("ldx") && mnem2.equals("txa") && mode2.equals(CpuAddressingMode.NON)) {
               if(mode1.equals(CpuAddressingMode.ZP) ||
                  mode1.equals(CpuAddressingMode.ABS) ||
                  mode1.equals(CpuAddressingMode.ZPY) ||
                  mode1.equals(CpuAddressingMode.ABY) ||
                  mode1.equals(CpuAddressingMode.IZX) ||
                  mode1.equals(CpuAddressingMode.IZY)) {
                  CpuOpcode laxOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lax", mode1, false);
                  if(laxOpcode != null) {
                     getLog().append("Replacing " + inst1.getAsm() + "; " + inst2.getAsm() + " with LAX");
                     inst1.setCpuOpcode(laxOpcode);
                     ref2.chunk.getLines().remove(inst2);
                     passModified = true;
                     overallModified = true;
                     break;
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 1c: LDA <mem>; STA <dst>; TAX -> LAX <mem>; STA <dst>
            // -------------------------------------------------------------
            if(mnem1.equals("lda") && mnem2.equals("sta") &&
               (mode1.equals(CpuAddressingMode.ZP) || mode1.equals(CpuAddressingMode.ABS) ||
                mode1.equals(CpuAddressingMode.IZY) || mode1.equals(CpuAddressingMode.ABY) ||
                mode1.equals(CpuAddressingMode.ZPY) || mode1.equals(CpuAddressingMode.IZX))) {
               int idx3 = findNextInstruction(allLines, nextIdx + 1);
               if(idx3 != -1) {
                  AsmInstruction instNext3 = (AsmInstruction) allLines.get(idx3).line;
                  if(instNext3.getCpuOpcode() != null && instNext3.getCpuOpcode().getMnemonic().equals("tax") &&
                     instNext3.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.NON)) {
                     Map<String, String> aliases = scopeAliasMap.get(currentScope);
                     if(!isSameLocation(inst1.getOperand1(), inst2.getOperand1(), aliases)) {
                        CpuOpcode laxOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lax", mode1, false);
                        if(laxOpcode != null) {
                           getLog().append("Replacing " + inst1.getAsm() + "; " + inst2.getAsm() + "; TAX with LAX");
                           inst1.setCpuOpcode(laxOpcode);
                           allLines.get(idx3).chunk.getLines().remove(instNext3);
                           passModified = true;
                           overallModified = true;
                           break;
                        }
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 1d: LDA <mem>; Bxx <target>; TAX -> LAX <mem>; Bxx <target>
            // -------------------------------------------------------------
            if(mnem1.equals("lda") && mnem2.startsWith("b") &&
               (mode1.equals(CpuAddressingMode.ZP) || mode1.equals(CpuAddressingMode.ABS) ||
                mode1.equals(CpuAddressingMode.IZY) || mode1.equals(CpuAddressingMode.ABY) ||
                mode1.equals(CpuAddressingMode.ZPY) || mode1.equals(CpuAddressingMode.IZX))) {
               int idx3 = findNextInstruction(allLines, nextIdx + 1);
               if(idx3 != -1) {
                  AsmInstruction instNext3 = (AsmInstruction) allLines.get(idx3).line;
                  if(instNext3.getCpuOpcode() != null && instNext3.getCpuOpcode().getMnemonic().equals("tax") &&
                     instNext3.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.NON)) {
                     String targetLabel = inst2.getOperand1();
                     int targetIdx = findLabelIndex(allLines, targetLabel, nextIdx);
                     if(targetIdx != -1 && isRegDeadAlongPath("X", allLines, targetIdx + 1, new HashSet<>(), currentScope, getProgram())) {
                        CpuOpcode laxOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lax", mode1, false);
                        if(laxOpcode != null) {
                           getLog().append("Lifting TAX into LAX before branch: " + inst1.getAsm() + "; " + inst2.getAsm() + "; TAX");
                           inst1.setCpuOpcode(laxOpcode);
                           allLines.get(idx3).chunk.getLines().remove(instNext3);
                           passModified = true;
                           overallModified = true;
                           break;
                        }
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 1e: LDX <mem>; LDY <mem> -> LAX <mem>; TAY
            // -------------------------------------------------------------
            if(mnem1.equals("ldx") && mnem2.equals("ldy") &&
               inst1.getOperand1() != null && inst1.getOperand1().equals(inst2.getOperand1()) &&
               (mode1.equals(CpuAddressingMode.ZP) || mode1.equals(CpuAddressingMode.ABS)) &&
               mode1.equals(mode2) &&
               !isVolatileOrHardwareOperand(inst1.getOperand1(), currentScope, resolver, getProgram())) {
               if(isRegDeadAlongPath("A", allLines, i, new HashSet<>(), currentScope, getProgram())) {
                  CpuOpcode laxOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lax", mode1, false);
                  CpuOpcode tayOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("tay", CpuAddressingMode.NON, false);
                  if(laxOpcode != null && tayOpcode != null) {
                     getLog().append("Replacing LDX " + inst1.getOperand1() + "; LDY " + inst2.getOperand1() + " with LAX; TAY");
                     inst1.setCpuOpcode(laxOpcode);
                     inst2.setCpuOpcode(tayOpcode);
                     inst2.setOperand1(null);
                     passModified = true;
                     overallModified = true;
                     break;
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 2: AND #{imm}; LSR -> ALR #{imm}
            // -------------------------------------------------------------
            if(mnem1.equals("and") && mode1.equals(CpuAddressingMode.IMM) &&
               mnem2.equals("lsr") && mode2.equals(CpuAddressingMode.NON)) {
               CpuOpcode alrOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("alr", CpuAddressingMode.IMM, false);
               if(alrOpcode != null) {
                  getLog().append("Replacing " + inst1.getAsm() + "; " + inst2.getAsm() + " with ALR");
                  inst1.setCpuOpcode(alrOpcode);
                  ref2.chunk.getLines().remove(inst2);
                  passModified = true;
                  overallModified = true;
                  break;
               }
            }

            // -------------------------------------------------------------
            // Pattern 2b: LSR; AND #{imm} -> ALR #{(imm << 1)}
            // -------------------------------------------------------------
            if(mnem1.equals("lsr") && mode1.equals(CpuAddressingMode.NON) &&
               mnem2.equals("and") && mode2.equals(CpuAddressingMode.IMM)) {
               Integer maskVal = AsmProgramStaticRegisterValues.getImmValue(inst2.getOperand1());
               if(maskVal != null && (maskVal & 0x80) == 0 && maskVal >= 0 && maskVal <= 127) {
                  if(!isFlagCLiveAfter(allLines, nextIdx, currentScope, getProgram())) {
                     CpuOpcode alrOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("alr", CpuAddressingMode.IMM, false);
                     if(alrOpcode != null) {
                        int alrImm = (maskVal << 1) & 0xff;
                        getLog().append("Replacing LSR; " + inst2.getAsm() + " with ALR #$" + Integer.toHexString(alrImm));
                        inst1.setCpuOpcode(alrOpcode);
                        inst1.setOperand1("$" + Integer.toHexString(alrImm));
                        ref2.chunk.getLines().remove(inst2);
                        passModified = true;
                        overallModified = true;
                        break;
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 2c: LSR; LSR; ASL -> ALR #$fc
            // -------------------------------------------------------------
            if(mnem1.equals("lsr") && mode1.equals(CpuAddressingMode.NON) &&
               mnem2.equals("lsr") && mode2.equals(CpuAddressingMode.NON)) {
               int idx3 = findNextInstruction(allLines, nextIdx + 1);
               if(idx3 != -1) {
                  AsmInstruction instNext3 = (AsmInstruction) allLines.get(idx3).line;
                  if(instNext3.getCpuOpcode() != null && instNext3.getCpuOpcode().getMnemonic().equals("asl") &&
                     instNext3.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.NON)) {
                     CpuOpcode alrOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("alr", CpuAddressingMode.IMM, false);
                     if(alrOpcode != null) {
                        getLog().append("Replacing LSR; LSR; ASL with ALR #$fc");
                        inst1.setCpuOpcode(alrOpcode);
                        inst1.setOperand1("$fc");
                        ref2.chunk.getLines().remove(inst2);
                        allLines.get(idx3).chunk.getLines().remove(instNext3);
                        passModified = true;
                        overallModified = true;
                        break;
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Helper for 2-instruction RMW sequences on the same memory operand:
            // inst1 <mem>; inst2 <mem> -> combined <mem>
            // -------------------------------------------------------------
            boolean sameOperand = inst1.getOperand1() != null && inst2.getOperand1() != null &&
                                  mode1.equals(mode2) &&
                                  (inst1.getOperand1().equals(inst2.getOperand1())
                                     || (resolver != null && resolver.resolve(inst1.getOperand1()).equals(resolver.resolve(inst2.getOperand1()))));

            if(sameOperand &&
               !isVolatileOrHardwareOperand(inst1.getOperand1(), currentScope, resolver, getProgram()) &&
               !isVolatileOrHardwareOperand(inst2.getOperand1(), currentScope, resolver, getProgram())) {
               String combinedMnemonic = null;

               if(mnem1.equals("dec") && mnem2.equals("cmp")) {
                  combinedMnemonic = "dcp";
               } else if(mnem1.equals("inc") && mnem2.equals("sbc")) {
                  combinedMnemonic = "isc";
               } else if(mnem1.equals("asl") && mnem2.equals("ora")) {
                  combinedMnemonic = "slo";
               } else if(mnem1.equals("rol") && mnem2.equals("and")) {
                  combinedMnemonic = "rla";
               } else if(mnem1.equals("lsr") && mnem2.equals("eor")) {
                  combinedMnemonic = "sre";
               } else if(mnem1.equals("ror") && mnem2.equals("adc")) {
                  combinedMnemonic = "rra";
               }

               if(combinedMnemonic != null) {
                  CpuOpcode combinedOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode(combinedMnemonic, mode1, false);
                  if(combinedOpcode != null) {
                     getLog().append("Replacing " + inst1.getAsm() + "; " + inst2.getAsm() + " with " + combinedMnemonic.toUpperCase());
                     inst1.setCpuOpcode(combinedOpcode);
                     ref2.chunk.getLines().remove(inst2);
                     passModified = true;
                     overallModified = true;
                     break;
                  }
               }
            }
         }
      }

      return overallModified;
   }

   private static boolean isSameLocation(String op1, String op2, Map<String, String> scopeAliases) {
      if(op1 == null || op2 == null) return false;
      if(op1.equals(op2)) return true;
      if(scopeAliases != null) {
         String r1 = scopeAliases.getOrDefault(op1, op1);
         String r2 = scopeAliases.getOrDefault(op2, op2);
         return r1.equals(r2);
      }
      return false;
   }
}
