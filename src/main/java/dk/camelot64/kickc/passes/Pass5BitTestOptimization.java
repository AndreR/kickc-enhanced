package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Symbol;
import dk.camelot64.kickc.model.types.SymbolType;

import java.util.*;

/**
 * Assembler Peephole Optimization: Bit-Test Optimization for Bit 7 (0x80) and Bit 6 (0x40).
 *
 * Replaces sequences of:
 *   LDA #$80; AND mem; [CMP #0;] BEQ/BNE target   (or LDA mem; AND #$80; [CMP #0;] BEQ/BNE target)
 * with:
 *   BIT mem; BPL/BMI target
 *
 * And for bit 6:
 *   LDA #$40; AND mem; [CMP #0;] BEQ/BNE target   (or LDA mem; AND #$40; [CMP #0;] BEQ/BNE target)
 * with:
 *   BIT mem; BVC/BVS target
 *
 * Saves 2 CPU cycles, 2 instruction bytes, and preserves register A intact.
 */
public class Pass5BitTestOptimization extends Pass5AsmOptimization {

   public Pass5BitTestOptimization(Program program) {
      super(program);
   }

   private static boolean isImmVal(String operand, int val) {
      if(operand == null) return false;
      String t = operand.trim();
      if(t.startsWith("#")) {
         t = t.substring(1).trim();
      }
      try {
         long num;
         if(t.startsWith("$")) {
            num = Long.parseLong(t.substring(1), 16);
         } else if(t.startsWith("0x") || t.startsWith("0X")) {
            num = Long.parseLong(t.substring(2), 16);
         } else if(t.startsWith("%")) {
            num = Long.parseLong(t.substring(1), 2);
         } else {
            num = Long.parseLong(t);
         }
         return num == val;
      } catch(NumberFormatException e) {
         return false;
      }
   }

   private static boolean isZeroCmp(AsmInstruction inst) {
      if(inst == null || inst.getCpuOpcode() == null) return false;
      String mnem = inst.getCpuOpcode().getMnemonic();
      if(!mnem.equals("cmp")) return false;
      if(!inst.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.IMM)) return false;
      return isImmVal(inst.getOperand1(), 0);
   }

   private boolean isADeadAfter(List<LineRef> allLines, int startIdx, String currentScope) {
      return isRegDeadAlongPath("A", allLines, startIdx, new HashSet<>(), currentScope, getProgram());
   }

   @Override
   public boolean optimize() {
      boolean overallModified = false;

      List<LineRef> allLines = new ArrayList<>();
      String currentScope = null;
      int lineIdx = 0;
      Map<String, Integer> labelIndices = new HashMap<>();

      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         for(AsmLine line : chunk.getLines()) {
            if(line instanceof AsmScopeBegin) {
               currentScope = ((AsmScopeBegin) line).getLabel();
            } else if(line instanceof AsmScopeEnd) {
               currentScope = null;
            } else if(line instanceof AsmLabel) {
               String lbl = ((AsmLabel) line).getLabel();
               if(lbl != null && !lbl.startsWith("!")) {
                  String key = (currentScope != null ? currentScope + "::" : "") + lbl;
                  labelIndices.put(key, lineIdx);
               }
            }
            allLines.add(new LineRef(lineIdx++, chunk, line, currentScope));
         }
      }

      for(int i = 0; i < allLines.size(); i++) {
            LineRef ref1 = allLines.get(i);
            if(!(ref1.line instanceof AsmInstruction)) continue;
            AsmInstruction inst1 = (AsmInstruction) ref1.line;
            if(inst1.getCpuOpcode() == null || inst1.isNoOptimize()) continue;

            // Find next instruction inst2
            int idx2 = -1;
            for(int j = i + 1; j < allLines.size(); j++) {
               LineRef cand = allLines.get(j);
               if(cand.line instanceof AsmComment || cand.line instanceof AsmConstant || cand.line instanceof AsmLabelDecl) continue;
               if(cand.line instanceof AsmInstruction) {
                  idx2 = j;
                  break;
               }
               break;
            }
            if(idx2 == -1) continue;

            LineRef ref2 = allLines.get(idx2);
            AsmInstruction inst2 = (AsmInstruction) ref2.line;
            if(inst2.getCpuOpcode() == null || inst2.isNoOptimize()) continue;

            // Find next instruction(s): could be optional CMP #0 followed by branch
            int idxNext = -1;
            for(int j = idx2 + 1; j < allLines.size(); j++) {
               LineRef cand = allLines.get(j);
               if(cand.line instanceof AsmComment || cand.line instanceof AsmConstant || cand.line instanceof AsmLabelDecl) continue;
               if(cand.line instanceof AsmInstruction) {
                  idxNext = j;
                  break;
               }
               break;
            }
            if(idxNext == -1) continue;

            LineRef refNext = allLines.get(idxNext);
            AsmInstruction instNext = (AsmInstruction) refNext.line;
            if(instNext.getCpuOpcode() == null || instNext.isNoOptimize()) continue;

            LineRef refCmpZero = null;
            int idxBranch = idxNext;
            LineRef refBranch = refNext;
            AsmInstruction instBranch = instNext;

            if(isZeroCmp(instNext)) {
               refCmpZero = refNext;
               // Find branch following CMP #0
               int idxBranchFound = -1;
               for(int j = idxNext + 1; j < allLines.size(); j++) {
                  LineRef cand = allLines.get(j);
                  if(cand.line instanceof AsmComment || cand.line instanceof AsmConstant || cand.line instanceof AsmLabelDecl) continue;
                  if(cand.line instanceof AsmInstruction) {
                     idxBranchFound = j;
                     break;
                  }
                  break;
               }
               if(idxBranchFound == -1) continue;
               idxBranch = idxBranchFound;
               refBranch = allLines.get(idxBranch);
               instBranch = (AsmInstruction) refBranch.line;
               if(instBranch.getCpuOpcode() == null || instBranch.isNoOptimize()) continue;
            }

            // Check if instBranch is BEQ or BNE
            String branchMnem = instBranch.getCpuOpcode().getMnemonic();
            if(!("beq".equals(branchMnem) || "bne".equals(branchMnem))) continue;
            if(instBranch.getCpuOpcode().getAddressingMode() != CpuAddressingMode.REL) continue;
            String jumpTarget = instBranch.getOperandJumpTarget();
            if(jumpTarget == null) continue;

            // Determine if inst1 and inst2 form an LDA/AND bitmask test
            String mnem1 = inst1.getCpuOpcode().getMnemonic();
            String mnem2 = inst2.getCpuOpcode().getMnemonic();
            CpuAddressingMode mode1 = inst1.getCpuOpcode().getAddressingMode();
            CpuAddressingMode mode2 = inst2.getCpuOpcode().getAddressingMode();

            int testBit = -1; // 7 for 0x80, 6 for 0x40
            String memOperand = null;
            CpuAddressingMode memMode = null;


            if("lda".equals(mnem1) && "and".equals(mnem2)) {
               if(CpuAddressingMode.IMM.equals(mode1) && (CpuAddressingMode.ZP.equals(mode2) || CpuAddressingMode.ABS.equals(mode2))) {
                  if(isImmVal(inst1.getOperand1(), 0x80)) {
                     testBit = 7;
                     memOperand = inst2.getOperand1();
                     memMode = mode2;
                  } else if(isImmVal(inst1.getOperand1(), 0x40)) {
                     testBit = 6;
                     memOperand = inst2.getOperand1();
                     memMode = mode2;
                  }
               } else if((CpuAddressingMode.ZP.equals(mode1) || CpuAddressingMode.ABS.equals(mode1)) && CpuAddressingMode.IMM.equals(mode2)) {
                  if(isImmVal(inst2.getOperand1(), 0x80)) {
                     testBit = 7;
                     memOperand = inst1.getOperand1();
                     memMode = mode1;
                  } else if(isImmVal(inst2.getOperand1(), 0x40)) {
                     testBit = 6;
                     memOperand = inst1.getOperand1();
                     memMode = mode1;
                  }
               }
            }

            if(testBit == -1 || memOperand == null || memMode == null) continue;

            // Check if A is dead on fallthrough path
            if(!isADeadAfter(allLines, idxBranch + 1, refBranch.scope)) continue;

            // Check if A is dead on jump target path (strictly within refBranch.scope)
            String targetKey = (refBranch.scope != null ? refBranch.scope + "::" : "") + jumpTarget;
            Integer targetLineIdx = labelIndices.get(targetKey);
            if(targetLineIdx == null && jumpTarget.startsWith("!")) {
               int resolvedIdx = findLabelIndex(allLines, jumpTarget, idxBranch, refBranch.scope);
               if(resolvedIdx != -1) targetLineIdx = resolvedIdx;
            }
            if(targetLineIdx != null) {
               if(!isADeadAfter(allLines, targetLineIdx + 1, refBranch.scope)) continue;
            } else {
               continue;
            }

            // Both paths don't read A before A is overwritten!
            // Synthesize BIT and corresponding branch
            boolean isZp = CpuAddressingMode.ZP.equals(memMode);
            CpuOpcode bitOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("bit", memMode, isZp);
            String newBranchMnem;
            if(testBit == 7) {
               newBranchMnem = "bne".equals(branchMnem) ? "bmi" : "bpl";
            } else {
               newBranchMnem = "bne".equals(branchMnem) ? "bvs" : "bvc";
            }
            CpuOpcode newBranchOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode(newBranchMnem, CpuAddressingMode.REL, false);

            getLog().append("Optimizing bit " + testBit + " test to BIT " + memOperand + " ; " + newBranchMnem + " " + jumpTarget);

            // Replace inst1 with BIT memOperand
            inst1.setCpuOpcode(bitOp);
            inst1.setOperand1(memOperand);
            inst1.setOperand2(null);
            inst1.setOperand3(null);

            // Remove inst2
            tombstoneLine(allLines, idx2);

            // Remove CMP #0 if present
            if(refCmpZero != null) {
               tombstoneLine(allLines, idxNext);
            }

            // Update instBranch to new branch opcode
            instBranch.setCpuOpcode(newBranchOp);

            overallModified = true;
         }

      return overallModified;
   }
}