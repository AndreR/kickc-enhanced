package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.AsmChunk;
import dk.camelot64.kickc.asm.AsmInstruction;
import dk.camelot64.kickc.asm.AsmLine;
import dk.camelot64.kickc.asm.AsmProgramStaticRegisterValues;
import dk.camelot64.kickc.model.Program;

import java.util.List;
import java.util.ListIterator;

/**
 * Maps out register values entering all instructions. Removes unnecessary loads / clears / sets
 */
public class Pass5UnnecesaryLoadElimination extends Pass5AsmOptimization {

   public Pass5UnnecesaryLoadElimination(Program program) {
      super(program);
   }

   @Override
   public boolean optimize() {
      AsmProgramStaticRegisterValues staticValues = new AsmProgramStaticRegisterValues(getAsmProgram());
      boolean modified = false;

      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         List<AsmLine> lines = chunk.getLines();
         ListIterator<AsmLine> lineIt = lines.listIterator();
         while(lineIt.hasNext()) {
            AsmLine line = lineIt.next();
            if(line instanceof AsmInstruction) {
               AsmInstruction instruction = (AsmInstruction) line;
               if(instruction.isNoOptimize()) {
                  continue;
               }
               CpuOpcode cpuOpcode = instruction.getCpuOpcode();

               if(cpuOpcode.getMnemonic().equals("lda") && cpuOpcode.getAddressingMode().equals(CpuAddressingMode.IMM)) {
                  String immValue = instruction.getOperand1();
                  AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
                  if(AsmProgramStaticRegisterValues.matchImm(instructionValues.getA(), immValue)) {
                     if(canRemoveRegister(lines, lineIt.previousIndex(), "A")) {
                        modified = remove(lineIt);
                     }
                  } else if(AsmProgramStaticRegisterValues.matchImm(instructionValues.getX(), immValue)) {
                     getLog().append("Replacing instruction " + instruction + " with TXA");
                     instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("txa", CpuAddressingMode.NON, false));
                     instruction.setOperand1(null);
                  } else if(AsmProgramStaticRegisterValues.matchImm(instructionValues.getY(), immValue)) {
                     getLog().append("Replacing instruction " + instruction + " with TYA");
                     instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("tya", CpuAddressingMode.NON, false));
                     instruction.setOperand1(null);
                  } else if(AsmProgramStaticRegisterValues.matchImm(instructionValues.getZ(), immValue)) {
                     getLog().append("Replacing instruction " + instruction + " with TZA");
                     instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("tza", CpuAddressingMode.NON, false));
                     instruction.setOperand1(null);
                  }
               }
               if(cpuOpcode.getMnemonic().equals("lda") && (cpuOpcode.getAddressingMode().equals(CpuAddressingMode.ZP) || cpuOpcode.getAddressingMode().equals(CpuAddressingMode.ABS))) {
                  String memValue = instruction.getOperand1();
                  AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
                  if(instructionValues.getaMem() != null && instructionValues.getaMem().equals(memValue)) {
                     if(canRemoveRegister(lines, lineIt.previousIndex(), "A")) {
                        modified = remove(lineIt);
                     }
                  } else if(instructionValues.getxMem() != null && instructionValues.getxMem().equals(memValue)) {
                     getLog().append("Replacing instruction " + instruction + " with TXA");
                     instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("txa", CpuAddressingMode.NON, false));
                     instruction.setOperand1(null);
                  } else if(instructionValues.getyMem() != null && instructionValues.getyMem().equals(memValue)) {
                     getLog().append("Replacing instruction " + instruction + " with TYA");
                     instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("tya", CpuAddressingMode.NON, false));
                     instruction.setOperand1(null);
                  } else if(instructionValues.getzMem() != null && instructionValues.getzMem().equals(memValue)) {
                     getLog().append("Replacing instruction " + instruction + " with TZA");
                     instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("tza", CpuAddressingMode.NON, false));
                     instruction.setOperand1(null);
                  }
               }
               if(cpuOpcode.getMnemonic().equals("ldx") && cpuOpcode.getAddressingMode().equals(CpuAddressingMode.IMM)) {
                  String immValue = instruction.getOperand1();
                  AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
                  if(AsmProgramStaticRegisterValues.matchImm(instructionValues.getX(), immValue)) {
                     if(canRemoveRegister(lines, lineIt.previousIndex(), "X")) {
                        modified = remove(lineIt);
                     }
                  } else if(AsmProgramStaticRegisterValues.matchImm(instructionValues.getA(), immValue)) {
                     getLog().append("Replacing instruction " + instruction + " with TAX");
                     instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("tax", CpuAddressingMode.NON, false));
                     instruction.setOperand1(null);
                  }
               }
               if(cpuOpcode.getMnemonic().equals("ldx") && (cpuOpcode.getAddressingMode().equals(CpuAddressingMode.ZP) || cpuOpcode.getAddressingMode().equals(CpuAddressingMode.ABS))) {
                  String memValue = instruction.getOperand1();
                  AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
                  if(instructionValues.getxMem() != null && instructionValues.getxMem().equals(memValue)) {
                     if(canRemoveRegister(lines, lineIt.previousIndex(), "X")) {
                        modified = remove(lineIt);
                     }
                  } else if(instructionValues.getaMem() != null && instructionValues.getaMem().equals(memValue)) {
                     getLog().append("Replacing instruction " + instruction + " with TAX");
                     instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("tax", CpuAddressingMode.NON, false));
                     instruction.setOperand1(null);
                  }
               }
               if(cpuOpcode.getMnemonic().equals("ldy") && cpuOpcode.getAddressingMode().equals(CpuAddressingMode.IMM)) {
                  String immValue = instruction.getOperand1();
                  AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
                  if(AsmProgramStaticRegisterValues.matchImm(instructionValues.getY(), immValue)) {
                     if(canRemoveRegister(lines, lineIt.previousIndex(), "Y")) {
                        modified = remove(lineIt);
                     }
                  } else if(AsmProgramStaticRegisterValues.matchImm(instructionValues.getA(), immValue)) {
                     getLog().append("Replacing instruction " + instruction + " with TAY");
                     instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("tay", CpuAddressingMode.NON, false));
                     instruction.setOperand1(null);
                  }
               }
               if(cpuOpcode.getMnemonic().equals("ldy") && (cpuOpcode.getAddressingMode().equals(CpuAddressingMode.ZP) || cpuOpcode.getAddressingMode().equals(CpuAddressingMode.ABS))) {
                  String memValue = instruction.getOperand1();
                  AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
                  if(instructionValues.getyMem() != null && instructionValues.getyMem().equals(memValue)) {
                     if(canRemoveRegister(lines, lineIt.previousIndex(), "Y")) {
                        modified = remove(lineIt);
                     }
                  } else if(instructionValues.getaMem() != null && instructionValues.getaMem().equals(memValue)) {
                     getLog().append("Replacing instruction " + instruction + " with TAY");
                     instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("tay", CpuAddressingMode.NON, false));
                     instruction.setOperand1(null);
                  }
               }
               if(cpuOpcode.getMnemonic().equals("ldz") && cpuOpcode.getAddressingMode().equals(CpuAddressingMode.IMM)) {
                  String immValue = instruction.getOperand1();
                  AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
                  if(AsmProgramStaticRegisterValues.matchImm(instructionValues.getZ(), immValue)) {
                     modified = remove(lineIt);
                  } else if(AsmProgramStaticRegisterValues.matchImm(instructionValues.getA(), immValue)) {
                     getLog().append("Replacing instruction " + instruction + " with TAZ");
                     instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("taz", CpuAddressingMode.NON, false));
                     instruction.setOperand1(null);
                  }
               }
               if(cpuOpcode.getMnemonic().equals("ldz") && (cpuOpcode.getAddressingMode().equals(CpuAddressingMode.ZP) || cpuOpcode.getAddressingMode().equals(CpuAddressingMode.ABS))) {
                  String memValue = instruction.getOperand1();
                  AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
                  if(instructionValues.getzMem() != null && instructionValues.getzMem().equals(memValue)) {
                     modified = remove(lineIt);
                  } else if(instructionValues.getaMem() != null && instructionValues.getaMem().equals(memValue)) {
                     getLog().append("Replacing instruction " + instruction + " with TAZ");
                     instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("taz", CpuAddressingMode.NON, false));
                     instruction.setOperand1(null);
                  }
               }
               if(cpuOpcode.getMnemonic().equals("clc")) {
                  AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
                  if(Boolean.FALSE.equals(instructionValues.getFlagC())) {
                     modified = remove(lineIt);
                  }
               }
               if(cpuOpcode.getMnemonic().equals("sec")) {
                  AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
                  if(Boolean.TRUE.equals(instructionValues.getFlagC())) {
                     modified = remove(lineIt);
                  }
               }
            }
         }
      }
      return modified;
   }


   private static boolean areFlagsNeededAfter(List<AsmLine> lines, int currentIdx) {
      for(int k = currentIdx + 1; k < lines.size(); k++) {
         AsmLine line = lines.get(k);
         if(line instanceof dk.camelot64.kickc.asm.AsmComment || line instanceof dk.camelot64.kickc.asm.AsmConstant || line instanceof dk.camelot64.kickc.asm.AsmLabelDecl) continue;
         if(line instanceof dk.camelot64.kickc.asm.AsmLabel || line instanceof dk.camelot64.kickc.asm.AsmScopeBegin || line instanceof dk.camelot64.kickc.asm.AsmScopeEnd || line instanceof dk.camelot64.kickc.asm.AsmInlineKickAsm) {
            return true;
         }
         if(line instanceof AsmInstruction) {
            AsmInstruction inst = (AsmInstruction) line;
            if(inst.getCpuOpcode() == null) return true;
            String mnem = inst.getCpuOpcode().getMnemonic();
            if(mnem.equals("beq") || mnem.equals("bne") || mnem.equals("bmi") || mnem.equals("bpl") ||
               mnem.equals("bcc") || mnem.equals("bcs") || mnem.equals("bvc") || mnem.equals("bvs")) {
               return true;
            }
            if(mnem.equals("sta") || mnem.equals("stx") || mnem.equals("sty") || mnem.equals("nop")) {
               continue;
            }
            return false;
         }
      }
      return false;
   }

   private static boolean areFlagsEstablishedForReg(List<AsmLine> lines, int currentIdx, String reg) {
      for(int p = currentIdx - 1; p >= 0; p--) {
         AsmLine line = lines.get(p);
         if(line instanceof dk.camelot64.kickc.asm.AsmComment || line instanceof dk.camelot64.kickc.asm.AsmConstant || line instanceof dk.camelot64.kickc.asm.AsmLabelDecl) continue;
         if(line instanceof dk.camelot64.kickc.asm.AsmLabel || line instanceof dk.camelot64.kickc.asm.AsmScopeBegin || line instanceof dk.camelot64.kickc.asm.AsmScopeEnd || line instanceof dk.camelot64.kickc.asm.AsmInlineKickAsm) {
            return false;
         }
         if(line instanceof AsmInstruction) {
            AsmInstruction prev = (AsmInstruction) line;
            if(prev.getCpuOpcode() == null) return false;
            String mnem = prev.getCpuOpcode().getMnemonic();
            if(mnem.equals("sta") || mnem.equals("stx") || mnem.equals("sty") || mnem.equals("nop")) {
               continue;
            }
            return Pass5IllegalOpcodeOptimization.doesInstructionSetZAndN(prev, reg);
         }
      }
      return false;
   }

   private static boolean canRemoveRegister(List<AsmLine> lines, int currentIdx, String reg) {
      return !areFlagsNeededAfter(lines, currentIdx) || areFlagsEstablishedForReg(lines, currentIdx, reg);
   }
}
