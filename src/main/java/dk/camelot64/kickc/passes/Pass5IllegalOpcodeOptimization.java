package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.TargetCpu;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembler Peephole Optimization for stable NMOS 6510 Unintended (Illegal) Opcodes
 * and redundant comparisons.
 * Combines pairs of standard instructions into single stable illegal opcodes
 * (e.g. LDA/TAX -> LAX, AND #imm; LSR -> ALR, DEC/CMP -> DCP, INC/SBC -> ISC, etc.)
 * and eliminates redundant zero comparisons (CMP/CPX/CPY #0 before BEQ/BNE/BMI/BPL).
 * Operates across adjacent statement chunks within the same basic block.
 * Only executes when the target CPU supports MOS6502_UNODC (e.g. mos6502x, rom6502x).
 */
public class Pass5IllegalOpcodeOptimization extends Pass5AsmOptimization {

   private static class LineRef {
      final AsmChunk chunk;
      final AsmLine line;

      LineRef(AsmChunk chunk, AsmLine line) {
         this.chunk = chunk;
         this.line = line;
      }
   }

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

         // Build a flat sequential list of all lines across all chunks
         List<LineRef> allLines = new ArrayList<>();
         for(AsmChunk chunk : getAsmProgram().getChunks()) {
            for(AsmLine line : chunk.getLines()) {
               allLines.add(new LineRef(chunk, line));
            }
         }

         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref1 = allLines.get(i);
            if(!(ref1.line instanceof AsmInstruction)) {
               continue;
            }
            AsmInstruction inst1 = (AsmInstruction) ref1.line;
            if(inst1.isNoOptimize()) {
               continue;
            }

            // Find next executable instruction across chunks (skipping comments and harmless metadata)
            int nextIdx = -1;
            LineRef ref2 = null;
            for(int j = i + 1; j < allLines.size(); j++) {
               LineRef candidate = allLines.get(j);
               if(candidate.line instanceof AsmComment || candidate.line instanceof AsmConstant || candidate.line instanceof AsmLabelDecl) {
                  continue;
               }
               if(candidate.line instanceof AsmInstruction) {
                  nextIdx = j;
                  ref2 = candidate;
                  break;
               }
               // Encountered label, scope change, directive, or data - cannot optimize across boundaries
               break;
            }

            if(ref2 == null) {
               continue;
            }
            AsmInstruction inst2 = (AsmInstruction) ref2.line;
            if(inst2.isNoOptimize()) {
               continue;
            }

            // Find next executable instruction after inst2 (for branch lookahead)
            int branchIdx = -1;
            LineRef ref3 = null;
            for(int k = nextIdx + 1; k < allLines.size(); k++) {
               LineRef candidate = allLines.get(k);
               if(candidate.line instanceof AsmComment || candidate.line instanceof AsmConstant || candidate.line instanceof AsmLabelDecl) {
                  continue;
               }
               if(candidate.line instanceof AsmInstruction) {
                  branchIdx = k;
                  ref3 = candidate;
                  break;
               }
               break;
            }
            AsmInstruction inst3 = ref3 != null ? (AsmInstruction) ref3.line : null;

            CpuOpcode op1 = inst1.getCpuOpcode();
            CpuOpcode op2 = inst2.getCpuOpcode();
            String mnem1 = op1.getMnemonic();
            String mnem2 = op2.getMnemonic();
            CpuAddressingMode mode1 = op1.getAddressingMode();
            CpuAddressingMode mode2 = op2.getAddressingMode();

            // -------------------------------------------------------------
            // Pattern 0: Redundant CMP #0 / CPX #0 / CPY #0 elimination
            // When inst1 already sets Z and N flags identically on the register,
            // and inst3 is a conditional branch testing Z or N (BEQ, BNE, BMI, BPL),
            // CMP #0 / CPX #0 / CPY #0 is 100% redundant and can be safely removed.
            // -------------------------------------------------------------
            if(inst3 != null && !inst3.isNoOptimize() && mode2.equals(CpuAddressingMode.IMM) && isZeroOperand(inst2.getOperand1())) {
               String mnem3 = inst3.getCpuOpcode() != null ? inst3.getCpuOpcode().getMnemonic() : "";
               boolean isZOrNBranch = mnem3.equals("beq") || mnem3.equals("bne") || mnem3.equals("bmi") || mnem3.equals("bpl");
               if(isZOrNBranch) {
                  boolean canEliminate = false;
                  if(mnem2.equals("cmp")) {
                     if(mnem1.equals("lda") || mnem1.equals("pla") ||
                        mnem1.equals("tax") || mnem1.equals("txa") ||
                        mnem1.equals("tay") || mnem1.equals("tya") ||
                        mnem1.equals("and") || mnem1.equals("ora") || mnem1.equals("eor") ||
                        mnem1.equals("lax") || mnem1.equals("alr") || mnem1.equals("anc") ||
                        ((mnem1.equals("asl") || mnem1.equals("lsr") || mnem1.equals("rol") || mnem1.equals("ror")) && mode1.equals(CpuAddressingMode.NON))) {
                        canEliminate = true;
                     }
                  } else if(mnem2.equals("cpx")) {
                     if(mnem1.equals("ldx") || mnem1.equals("inx") || mnem1.equals("dex") ||
                        mnem1.equals("tax") || mnem1.equals("tsx") || mnem1.equals("lax") || mnem1.equals("axs")) {
                        canEliminate = true;
                     }
                  } else if(mnem2.equals("cpy")) {
                     if(mnem1.equals("ldy") || mnem1.equals("iny") || mnem1.equals("dey") || mnem1.equals("tay")) {
                        canEliminate = true;
                     }
                  }

                  if(canEliminate) {
                     getLog().append("Eliminating redundant " + inst2.getAsm() + " after " + inst1.getAsm() + " before " + inst3.getAsm());
                     ref2.chunk.getLines().remove(inst2);
                     passModified = true;
                     overallModified = true;
                     break;
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 0b: Redundant LDA <mem> after DEC <mem> / INC <mem>
            // DEC/INC already sets Z and N flags. If followed by a Z/N branch (BEQ/BNE/BMI/BPL)
            // and the fallthrough instruction overwrites A, LDA is completely dead.
            // -------------------------------------------------------------
            if(inst3 != null && !inst3.isNoOptimize() && branchIdx != -1 &&
               (mnem1.equals("dec") || mnem1.equals("inc")) && mnem2.equals("lda") &&
               inst1.getOperand1() != null && inst1.getOperand1().equals(inst2.getOperand1()) && mode1.equals(mode2)) {
               String mnem3 = inst3.getCpuOpcode() != null ? inst3.getCpuOpcode().getMnemonic() : "";
               boolean isZOrNBranch = mnem3.equals("beq") || mnem3.equals("bne") || mnem3.equals("bmi") || mnem3.equals("bpl");
               if(isZOrNBranch) {
                  // Check if fallthrough instruction (after branch inst3) overwrites A
                  LineRef ref4 = null;
                  for(int m = branchIdx + 1; m < allLines.size(); m++) {
                     LineRef cand = allLines.get(m);
                     if(cand.line instanceof AsmComment || cand.line instanceof AsmConstant || cand.line instanceof AsmLabelDecl) continue;
                     if(cand.line instanceof AsmInstruction) {
                        ref4 = cand;
                        break;
                     }
                     break;
                  }
                  if(ref4 != null) {
                     AsmInstruction inst4 = (AsmInstruction) ref4.line;
                     String mnem4 = inst4.getCpuOpcode() != null ? inst4.getCpuOpcode().getMnemonic() : "";
                     if(mnem4.equals("lda") || mnem4.equals("pla") || mnem4.equals("txa") || mnem4.equals("tya")) {
                        getLog().append("Eliminating redundant load " + inst2.getAsm() + " after " + inst1.getAsm() + " before " + inst3.getAsm());
                        ref2.chunk.getLines().remove(inst2);
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
            // Stable addressing modes: ZP, ABS, ZPY, ABY, IZX, IZY
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
            // Pattern 2: AND #{imm}; LSR -> ALR #{imm}
            // ALR executes A = (A & #{imm}) >> 1 and sets Carry to bit 0.
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
            // Helper for 2-instruction RMW sequences on the same memory operand:
            // inst1 <mem>; inst2 <mem> -> combined <mem>
            // -------------------------------------------------------------
            boolean sameOperand = inst1.getOperand1() != null &&
                                  inst1.getOperand1().equals(inst2.getOperand1()) &&
                                  mode1.equals(mode2);

            if(sameOperand) {
               String combinedMnemonic = null;

               // Pattern 3: DEC <mem>; CMP <mem> -> DCP <mem>
               if(mnem1.equals("dec") && mnem2.equals("cmp")) {
                  combinedMnemonic = "dcp";
               }
               // Pattern 4: INC <mem>; SBC <mem> -> ISC <mem>
               else if(mnem1.equals("inc") && mnem2.equals("sbc")) {
                  combinedMnemonic = "isc";
               }
               // Pattern 5: ASL <mem>; ORA <mem> -> SLO <mem>
               else if(mnem1.equals("asl") && mnem2.equals("ora")) {
                  combinedMnemonic = "slo";
               }
               // Pattern 6: ROL <mem>; AND <mem> -> RLA <mem>
               else if(mnem1.equals("rol") && mnem2.equals("and")) {
                  combinedMnemonic = "rla";
               }
               // Pattern 7: LSR <mem>; EOR <mem> -> SRE <mem>
               else if(mnem1.equals("lsr") && mnem2.equals("eor")) {
                  combinedMnemonic = "sre";
               }
               // Pattern 8: ROR <mem>; ADC <mem> -> RRA <mem>
               else if(mnem1.equals("ror") && mnem2.equals("adc")) {
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

   /**
    * Checks if an ASM operand string evaluates to integer 0.
    */
   private static boolean isZeroOperand(String op) {
      if(op == null) return false;
      String trimmed = op.trim();
      if(trimmed.startsWith("#")) trimmed = trimmed.substring(1).trim();
      if(trimmed.equals("0") || trimmed.equals("$0") || trimmed.equals("$00") || trimmed.equals("0x0") || trimmed.equals("0x00")) {
         return true;
      }
      try {
         if(trimmed.startsWith("$")) {
            return Integer.parseInt(trimmed.substring(1), 16) == 0;
         } else if(trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return Integer.parseInt(trimmed.substring(2), 16) == 0;
         } else {
            return Integer.parseInt(trimmed) == 0;
         }
      } catch(NumberFormatException e) {
         return false;
      }
   }
}
