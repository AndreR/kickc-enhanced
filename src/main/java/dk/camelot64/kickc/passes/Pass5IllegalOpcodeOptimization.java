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
                  tombstoneLine(allLines, nextIdx);
                  passModified = true;
                  overallModified = true;
                  continue;
               } else if(mnem2.equals("sta") || mnem2.equals("stx")) {
                  int i3 = findNextInstruction(allLines, nextIdx + 1);
                  if(i3 != -1) {
                     AsmInstruction instB = (AsmInstruction) allLines.get(i3).line;
                     if(instB.getCpuOpcode() != null && instB.getCpuOpcode().getMnemonic().equals("txa")) {
                        getLog().append("Eliminating redundant TXA after LAX and store");
                        tombstoneLine(allLines, i3);
                        passModified = true;
                        overallModified = true;
                        continue;
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
                     tombstoneLine(allLines, nextIdx);
                     passModified = true;
                     overallModified = true;
                     continue;
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
                     tombstoneLine(allLines, nextIdx);
                     passModified = true;
                     overallModified = true;
                     continue;
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 1c: LDA <mem>; STA <dst>; TAX -> LAX <mem>; STA <dst>
            // -------------------------------------------------------------
            if(mnem1.equals("lda") && mnem2.equals("sta") &&
               !CpuAddressingMode.ABX.equals(mode2) && !CpuAddressingMode.ZPX.equals(mode2) && !CpuAddressingMode.IZX.equals(mode2) &&
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
                           tombstoneLine(allLines, idx3);
                           passModified = true;
                           overallModified = true;
                           continue;
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
                           tombstoneLine(allLines, idx3);
                           passModified = true;
                           overallModified = true;
                           continue;
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
                     continue;
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
                  tombstoneLine(allLines, nextIdx);
                  passModified = true;
                  overallModified = true;
                  continue;
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
                        tombstoneLine(allLines, nextIdx);
                        passModified = true;
                        overallModified = true;
                        continue;
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
                        tombstoneLine(allLines, nextIdx);
                        tombstoneLine(allLines, idx3);
                        passModified = true;
                        overallModified = true;
                        continue;
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
               !isVolatileOrHardwareOperand(inst1, currentScope, resolver, getProgram()) &&
               !isVolatileOrHardwareOperand(inst2, currentScope, resolver, getProgram())) {
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
                     tombstoneLine(allLines, nextIdx);
                     passModified = true;
                     overallModified = true;
                     continue;
                  }
               }
            }

            // -------------------------------------------------------------
            // ILL-04: RMW+ALU folding across intervening LDA src:
            // RMW m; LDA src; ALU m -> LDA src; COMBINED m
            // -------------------------------------------------------------
            if((mnem1.equals("dec") || mnem1.equals("inc") || mnem1.equals("asl") || mnem1.equals("rol") || mnem1.equals("lsr") || mnem1.equals("ror")) &&
               !mode1.equals(CpuAddressingMode.NON) && mnem2.equals("lda") && inst1.getOperand1() != null &&
               !isVolatileOrHardwareOperand(inst1, currentScope, resolver, getProgram()) &&
               !isVolatileOrHardwareOperand(inst2, currentScope, resolver, getProgram())) {
               int idx3 = findNextInstruction(allLines, nextIdx + 1);
               if(idx3 != -1) {
                  AsmInstruction inst3 = (AsmInstruction) allLines.get(idx3).line;
                  if(inst3.getCpuOpcode() != null && !inst3.isNoOptimize() &&
                     !isVolatileOrHardwareOperand(inst3, currentScope, resolver, getProgram())) {
                     String mnem3 = inst3.getCpuOpcode().getMnemonic();
                     CpuAddressingMode mode3 = inst3.getCpuOpcode().getAddressingMode();
                     String combinedMnem = null;
                     if(mnem1.equals("dec") && mnem3.equals("cmp")) combinedMnem = "dcp";
                     else if(mnem1.equals("inc") && mnem3.equals("sbc")) combinedMnem = "isc";
                     else if(mnem1.equals("asl") && mnem3.equals("ora")) combinedMnem = "slo";
                     else if(mnem1.equals("rol") && mnem3.equals("and")) combinedMnem = "rla";
                     else if(mnem1.equals("lsr") && mnem3.equals("eor")) combinedMnem = "sre";
                     else if(mnem1.equals("ror") && mnem3.equals("adc")) combinedMnem = "rra";
                     if(combinedMnem != null && mode1.equals(mode3) && inst3.getOperand1() != null &&
                        (inst1.getOperand1().equals(inst3.getOperand1()) || (resolver != null && resolver.resolve(inst1.getOperand1()).equals(resolver.resolve(inst3.getOperand1()))))) {
                        // KRIT-05: Strict pointer/index aliasing guard before hoisting LDA src before RMW m.
                        // If mode1 is indexed or indirect (ZPX/ZPY/ABX/ABY/IZX/IZY), ONLY allow immediate mode2!
                        boolean srcDisjoint = false;
                        if(mode2.equals(CpuAddressingMode.IMM)) {
                           srcDisjoint = true;
                        } else if((mode1.equals(CpuAddressingMode.ZP) || mode1.equals(CpuAddressingMode.ABS)) &&
                                  (mode2.equals(CpuAddressingMode.ZP) || mode2.equals(CpuAddressingMode.ABS)) &&
                                  inst2.getOperand1() != null) {
                           String canon1 = resolver != null ? resolver.resolve(inst1.getOperand1()) : inst1.getOperand1().trim();
                           String canon2 = resolver != null ? resolver.resolve(inst2.getOperand1()) : inst2.getOperand1().trim();
                           if(!inst2.getOperand1().equals(inst1.getOperand1()) && !canon2.equals(canon1)) {
                              String base1 = getBaseVar(canon1);
                              String base2 = getBaseVar(canon2);
                              boolean num1 = parseHexOrDecAddress(base1) != null;
                              boolean num2 = parseHexOrDecAddress(base2) != null;
                              if(base1.equals(base2)) {
                                 int off1 = getOffset(canon1);
                                 int off2 = getOffset(canon2);
                                 srcDisjoint = (off1 >= 0 && off2 >= 0 && off1 != off2);
                              } else if(num1 == num2) {
                                 // Both are distinct concrete numeric addresses OR both are distinct non-aliased symbols
                                 // Guard against symbolic expressions like arr+x where offset isn't a plain integer
                                 srcDisjoint = (getOffset(canon1) >= 0 && getOffset(canon2) >= 0);
                              }
                           }
                        }
                        if(srcDisjoint) {
                           CpuOpcode combinedOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode(combinedMnem, mode1, false);
                           if(combinedOp != null) {
                              getLog().append("ILL-04: Folding " + inst1.getAsm() + "; " + inst2.getAsm() + "; " + inst3.getAsm() + " -> " + inst2.getAsm() + "; " + combinedMnem + " " + inst3.getOperand1());
                              inst3.setCpuOpcode(combinedOp);
                              tombstoneLine(allLines, i);
                              passModified = true;
                              overallModified = true;
                              continue;
                           }
                        }
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // ILL-05: ANC #imm ($0B)
            // Case A: AND #imm; CLC (with (imm & $80) == 0) -> ANC #imm
            // Case B: AND #imm; CMP #$80; ROR -> ANC #imm; ROR
            // -------------------------------------------------------------
            if(mnem1.equals("and") && mode1.equals(CpuAddressingMode.IMM)) {
               CpuOpcode ancOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("anc", CpuAddressingMode.IMM, false);
               if(ancOp != null) {
                  Integer immVal = AsmProgramStaticRegisterValues.getImmValue(inst1.getOperand1());
                  if(immVal != null && (immVal & 0x80) == 0 && mnem2.equals("clc") && mode2.equals(CpuAddressingMode.NON)) {
                     getLog().append("ILL-05A: Replacing " + inst1.getAsm() + "; CLC with ANC");
                     inst1.setCpuOpcode(ancOp);
                     tombstoneLine(allLines, nextIdx);
                     passModified = true;
                     overallModified = true;
                     continue;
                  }
                  if(mnem2.equals("cmp") && mode2.equals(CpuAddressingMode.IMM)) {
                     Integer cmpVal = AsmProgramStaticRegisterValues.getImmValue(inst2.getOperand1());
                     if(cmpVal != null && cmpVal == 0x80) {
                        int idx3 = findNextInstruction(allLines, nextIdx + 1);
                        if(idx3 != -1) {
                           AsmInstruction inst3 = (AsmInstruction) allLines.get(idx3).line;
                           if(inst3.getCpuOpcode() != null && inst3.getCpuOpcode().getMnemonic().equals("ror") &&
                              inst3.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.NON)) {
                              getLog().append("ILL-05B: Replacing " + inst1.getAsm() + "; CMP #$80; ROR with ANC; ROR");
                              inst1.setCpuOpcode(ancOp);
                              tombstoneLine(allLines, nextIdx);
                              passModified = true;
                              overallModified = true;
                              continue;
                           }
                        }
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // ILL-06: SAX ($87/$8F/$83/$97) - strictly ZP/ABS/ZPY/IZX only
            // Case A: TXA; AND #imm; STA dst -> LDA #imm; SAX dst
            // -------------------------------------------------------------
            if(mnem1.equals("txa") && mode1.equals(CpuAddressingMode.NON) &&
               mnem2.equals("and") && mode2.equals(CpuAddressingMode.IMM)) {
               int idx3 = findNextInstruction(allLines, nextIdx + 1);
               if(idx3 != -1) {
                  AsmInstruction inst3 = (AsmInstruction) allLines.get(idx3).line;
                  if(inst3.getCpuOpcode() != null && !inst3.isNoOptimize() && inst3.getCpuOpcode().getMnemonic().equals("sta")) {
                     CpuAddressingMode mode3 = inst3.getCpuOpcode().getAddressingMode();
                     boolean validSaxMode = mode3.equals(CpuAddressingMode.ZP) || mode3.equals(CpuAddressingMode.ABS) ||
                                            mode3.equals(CpuAddressingMode.ZPY) || mode3.equals(CpuAddressingMode.IZX);
                     if(validSaxMode &&
                        isRegDeadAlongPath("A", allLines, idx3 + 1, new HashSet<>(), currentScope, getProgram()) &&
                        (getLiveFlagsAfter(allLines, idx3, currentScope, getProgram()) & FLAG_NZ) == 0) {
                        CpuOpcode ldaImm = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lda", CpuAddressingMode.IMM, false);
                        CpuOpcode saxOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("sax", mode3, false);
                        if(ldaImm != null && saxOp != null) {
                           getLog().append("ILL-06A: Folding TXA; AND #" + inst2.getOperand1() + "; STA " + inst3.getOperand1() + " -> LDA; SAX");
                           inst2.setCpuOpcode(ldaImm);
                           inst3.setCpuOpcode(saxOp);
                           tombstoneLine(allLines, i);
                           passModified = true;
                           overallModified = true;
                           continue;
                        }
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // ILL-06B & C: STX $ff; AND $ff [; STA dst]
            // -------------------------------------------------------------
            if(mnem1.equals("stx") && mode1.equals(CpuAddressingMode.ZP) &&
               mnem2.equals("and") && mode2.equals(CpuAddressingMode.ZP) &&
               inst1.getOperand1() != null && inst1.getOperand1().equals(inst2.getOperand1()) &&
               ("$ff".equalsIgnoreCase(inst1.getOperand1().trim()) || "255".equals(inst1.getOperand1().trim()))) {
               int idx3 = findNextInstruction(allLines, nextIdx + 1);
               if(idx3 != -1) {
                  AsmInstruction inst3 = (AsmInstruction) allLines.get(idx3).line;
                  if(inst3.getCpuOpcode() != null && !inst3.isNoOptimize() && inst3.getCpuOpcode().getMnemonic().equals("sta")) {
                     CpuAddressingMode mode3 = inst3.getCpuOpcode().getAddressingMode();
                     boolean validSaxMode = mode3.equals(CpuAddressingMode.ZP) || mode3.equals(CpuAddressingMode.ABS) ||
                                            mode3.equals(CpuAddressingMode.ZPY) || mode3.equals(CpuAddressingMode.IZX);
                     if(validSaxMode &&
                        isRegDeadAlongPath("A", allLines, idx3 + 1, new HashSet<>(), currentScope, getProgram()) &&
                        isZpFfDeadAlongPath(allLines, idx3 + 1, new HashSet<>(), currentScope, resolver) &&
                        (getLiveFlagsAfter(allLines, idx3, currentScope, getProgram()) & FLAG_NZ) == 0) {
                        CpuOpcode saxOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("sax", mode3, false);
                        if(saxOp != null) {
                           getLog().append("ILL-06B: Folding STX $ff; AND $ff; STA " + inst3.getOperand1() + " -> SAX");
                           inst3.setCpuOpcode(saxOp);
                           tombstoneLine(allLines, i);
                           tombstoneLine(allLines, nextIdx);
                           passModified = true;
                           overallModified = true;
                           continue;
                        }
                     }
                  }
               }
               // ILL-06C: STX $ff; AND $ff -> AXS #0; TXA (when X, C, and $ff are dead after AND)
               if(isRegDeadAlongPath("X", allLines, nextIdx + 1, new HashSet<>(), currentScope, getProgram()) &&
                  isZpFfDeadAlongPath(allLines, nextIdx + 1, new HashSet<>(), currentScope, resolver) &&
                  !isFlagCLiveAfter(allLines, nextIdx, currentScope, getProgram())) {
                  CpuOpcode axsOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("axs", CpuAddressingMode.IMM, false);
                  CpuOpcode txaOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("txa", CpuAddressingMode.NON, false);
                  if(axsOp != null && txaOp != null) {
                     getLog().append("ILL-06C: Replacing STX $ff; AND $ff with AXS #0; TXA");
                     inst1.setCpuOpcode(axsOp);
                     inst1.setOperand1("0");
                     inst2.setCpuOpcode(txaOp);
                     inst2.setOperand1(null);
                     passModified = true;
                     overallModified = true;
                     continue;
                  }
               }
            }

            // -------------------------------------------------------------
            // ILL-07: AXS #imm ($CB) for constant X stepping
            // Case A: TXA; CLC; ADC #k; TAX (k in 3..255) -> TXA; AXS #(256 - k)
            // Case B: TXA; SEC; SBC #k; TAX (k in 3..255) -> TXA; AXS #k
            // -------------------------------------------------------------
            if(mnem1.equals("txa") && mode1.equals(CpuAddressingMode.NON) &&
               ((mnem2.equals("clc") || mnem2.equals("sec")) && mode2.equals(CpuAddressingMode.NON))) {
               int idx3 = findNextInstruction(allLines, nextIdx + 1);
               int idx4 = (idx3 != -1) ? findNextInstruction(allLines, idx3 + 1) : -1;
               if(idx4 != -1) {
                  AsmInstruction inst3 = (AsmInstruction) allLines.get(idx3).line;
                  AsmInstruction inst4 = (AsmInstruction) allLines.get(idx4).line;
                  if(inst3.getCpuOpcode() != null && !inst3.isNoOptimize() && inst4.getCpuOpcode() != null && !inst4.isNoOptimize() &&
                     inst4.getCpuOpcode().getMnemonic().equals("tax") && inst4.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.NON)) {
                     String mnem3 = inst3.getCpuOpcode().getMnemonic();
                     boolean isAdd = mnem2.equals("clc") && mnem3.equals("adc") && inst3.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.IMM);
                     boolean isSub = mnem2.equals("sec") && mnem3.equals("sbc") && inst3.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.IMM);
                     if(isAdd || isSub) {
                        Integer k = AsmProgramStaticRegisterValues.getImmValue(inst3.getOperand1());
                        if(k != null && k >= 3 && k <= 255 &&
                           isRegDeadAlongPath("A", allLines, idx4 + 1, new HashSet<>(), currentScope, getProgram()) &&
                           (getLiveFlagsAfter(allLines, idx4, currentScope, getProgram()) & FLAG_V) == 0) {
                           CpuOpcode axsOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("axs", CpuAddressingMode.IMM, false);
                           if(axsOp != null) {
                              int axsImm = isAdd ? ((256 - k) & 0xff) : (k & 0xff);
                              getLog().append("ILL-07: Folding TXA; " + mnem2 + "; " + mnem3 + " #" + k + "; TAX -> TXA; AXS #$" + Integer.toHexString(axsImm));
                              inst3.setCpuOpcode(axsOp);
                              inst3.setOperand1("$" + Integer.toHexString(axsImm));
                              tombstoneLine(allLines, nextIdx);
                              tombstoneLine(allLines, idx4);
                              passModified = true;
                              overallModified = true;
                              continue;
                           }
                        }
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // ILL-08A: Memory loop counter via DCP:
            // DEC m; LDA m; CMP #c; BEQ/BNE target -> LDA #c; DCP m; BEQ/BNE target
            // -------------------------------------------------------------
            if(mnem1.equals("dec") && (mode1.equals(CpuAddressingMode.ZP) || mode1.equals(CpuAddressingMode.ABS)) &&
               mnem2.equals("lda") && mode1.equals(mode2) &&
               inst1.getOperand1() != null && inst2.getOperand1() != null &&
               (inst1.getOperand1().equals(inst2.getOperand1()) || (resolver != null && resolver.resolve(inst1.getOperand1()).equals(resolver.resolve(inst2.getOperand1())))) &&
               !isVolatileOrHardwareOperand(inst1.getOperand1(), currentScope, resolver, getProgram())) {
               int idx3 = findNextInstruction(allLines, nextIdx + 1);
               int idx4 = (idx3 != -1) ? findNextInstruction(allLines, idx3 + 1) : -1;
               if(idx4 != -1) {
                  AsmInstruction inst3 = (AsmInstruction) allLines.get(idx3).line;
                  AsmInstruction inst4 = (AsmInstruction) allLines.get(idx4).line;
                  if(inst3.getCpuOpcode() != null && !inst3.isNoOptimize() &&
                     inst3.getCpuOpcode().getMnemonic().equals("cmp") && inst3.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.IMM) &&
                     inst4.getCpuOpcode() != null && !inst4.isNoOptimize()) {
                     String brMnem = inst4.getCpuOpcode().getMnemonic();
                     if(brMnem.equals("beq") || brMnem.equals("bne")) {
                        int targetIdx = findLabelIndex(allLines, inst4.getOperand1(), idx4);
                        boolean aDeadTarget = targetIdx != -1 && isRegDeadAlongPath("A", allLines, targetIdx + 1, new HashSet<>(), currentScope, getProgram());
                        boolean aDeadFall = isRegDeadAlongPath("A", allLines, idx4 + 1, new HashSet<>(), currentScope, getProgram());
                        if(aDeadTarget && aDeadFall && (getLiveFlagsAfter(allLines, idx4, currentScope, getProgram()) & (FLAG_C | FLAG_N)) == 0) {
                           CpuOpcode ldaImm = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lda", CpuAddressingMode.IMM, false);
                           CpuOpcode dcpOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("dcp", mode1, false);
                           if(ldaImm != null && dcpOp != null) {
                              String cImm = inst3.getOperand1();
                              String mOp = inst1.getOperand1();
                              getLog().append("ILL-08A: Folding DEC " + mOp + "; LDA " + mOp + "; CMP #" + cImm + "; " + brMnem + " -> LDA #" + cImm + "; DCP " + mOp);
                              inst1.setCpuOpcode(ldaImm);
                              inst1.setOperand1(cImm);
                              inst2.setCpuOpcode(dcpOp);
                              inst2.setOperand1(mOp);
                              tombstoneLine(allLines, idx3);
                              passModified = true;
                              overallModified = true;
                              continue;
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      return overallModified;
   }

   private static boolean isZpFfDeadAlongPath(List<LineRef> allLines, int startIdx, Set<String> visitedLabels, String currentScope, CanonicalResolver resolver) {
      for(int m = startIdx; m < allLines.size(); m++) {
         LineRef cand = allLines.get(m);
         if(cand.line instanceof AsmComment || cand.line instanceof AsmConstant || cand.line instanceof AsmLabelDecl) continue;
         if(cand.line instanceof AsmLabel) continue;
         if(cand.line instanceof AsmInstruction inst) {
            CpuOpcode op = inst.getCpuOpcode();
            if(op == null) return false;
            String mnem = op.getMnemonic();
            CpuAddressingMode mode = op.getAddressingMode();
            String operand = inst.getOperand1();

            if(mnem.equals("rts") || mnem.equals("rti")) {
               return true; // $ff is transient scratch ZP, not live across procedure return
            }
            if(mnem.equals("jsr")) {
               return false;
            }
            if(mnem.equals("jmp")) {
               if(mode.equals(CpuAddressingMode.IND) || mode.equals(CpuAddressingMode.IAX)) return false;
               String target = inst.getOperandJumpTarget();
               if(target != null && visitedLabels.add(target)) {
                  int targetIdx = findLabelIndex(allLines, target, m, currentScope);
                  if(targetIdx != -1) {
                     return isZpFfDeadAlongPath(allLines, targetIdx + 1, visitedLabels, currentScope, resolver);
                  }
               }
               return false;
            }
            if(mnem.startsWith("b")) {
               // Conditional branch: $ff must be dead on both fallthrough and branch target
               String target = inst.getOperandJumpTarget();
               if(target != null && visitedLabels.size() < 4 && visitedLabels.add(target)) {
                  int targetIdx = findLabelIndex(allLines, target, m, currentScope);
                  if(targetIdx != -1) {
                     return isZpFfDeadAlongPath(allLines, m + 1, new HashSet<>(visitedLabels), currentScope, resolver)
                         && isZpFfDeadAlongPath(allLines, targetIdx + 1, new HashSet<>(visitedLabels), currentScope, resolver);
                  }
               }
               return false;
            }
            if(operand != null) {
               String canon = resolver != null ? resolver.resolve(operand) : operand.trim();
               boolean isFf = isZpAddressOperand(operand, "$ff") || isZpAddressOperand(canon, "$ff") || "255".equals(operand.trim());
               boolean isFe = isZpAddressOperand(operand, "$fe") || isZpAddressOperand(canon, "$fe") || "254".equals(operand.trim());
               // Indirect addressing through ($fe) reads both $fe and $ff!
               if(isIndirectAddressingMode(mode) && (isFe || isFf)) {
                  return false;
               }
               if(mode == CpuAddressingMode.ZPX || mode == CpuAddressingMode.ZPY) {
                  return false;
               }
               if((mnem.equals("sta") || mnem.equals("stx") || mnem.equals("sty") || mnem.equals("sax")) &&
                  mode == CpuAddressingMode.ZP && isFf) {
                  return true; // $ff overwritten before read
               }
               if(isFf) {
                  return false; // $ff read or RMW
               }
            }
            continue;
         }
         break;
      }
      return false;
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
