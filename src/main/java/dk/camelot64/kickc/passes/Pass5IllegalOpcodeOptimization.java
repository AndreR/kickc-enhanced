package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.TargetCpu;

import java.util.List;

/**
 * Assembler Peephole Optimization for stable NMOS 6510 Unintended (Illegal) Opcodes.
 * Combines pairs of standard instructions into single stable illegal opcodes
 * (e.g. LDA/TAX -> LAX, AND #imm; LSR -> ALR, DEC/CMP -> DCP, INC/SBC -> ISC, etc.)
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

      boolean modified = false;

      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         List<AsmLine> lines = chunk.getLines();
         for(int i = 0; i < lines.size(); i++) {
            AsmLine line = lines.get(i);
            if(!(line instanceof AsmInstruction)) {
               continue;
            }
            AsmInstruction inst1 = (AsmInstruction) line;
            if(inst1.isNoOptimize()) {
               continue;
            }

            // Find next executable instruction in this chunk (skipping comments and harmless metadata)
            int nextIdx = -1;
            AsmInstruction inst2 = null;
            for(int j = i + 1; j < lines.size(); j++) {
               AsmLine candidate = lines.get(j);
               if(candidate instanceof AsmComment || candidate instanceof AsmConstant || candidate instanceof AsmLabelDecl) {
                  continue;
               }
               if(candidate instanceof AsmInstruction) {
                  nextIdx = j;
                  inst2 = (AsmInstruction) candidate;
                  break;
               }
               // Encountered label, scope change, directive, or data - cannot optimize across boundaries
               break;
            }

            if(inst2 == null || inst2.isNoOptimize()) {
               continue;
            }

            CpuOpcode op1 = inst1.getCpuOpcode();
            CpuOpcode op2 = inst2.getCpuOpcode();
            String mnem1 = op1.getMnemonic();
            String mnem2 = op2.getMnemonic();
            CpuAddressingMode mode1 = op1.getAddressingMode();
            CpuAddressingMode mode2 = op2.getAddressingMode();

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
                     lines.remove(nextIdx);
                     modified = true;
                     continue;
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
                  lines.remove(nextIdx);
                  modified = true;
                  continue;
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
                     lines.remove(nextIdx);
                     modified = true;
                     continue;
                  }
               }
            }
         }
      }

      return modified;
   }
}
