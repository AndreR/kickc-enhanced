package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;

import java.util.List;
import java.util.Map;

/**
 * Maps out register values entering all instructions. Removes unnecessary loads,
 * dead flag-only instructions (CLC/SEC/CMP/CPX/CPY), and redundant clears/sets
 * using bit-accurate {N, Z, C, V} backward flag liveness and volatile/I/O guards.
 */
public class Pass5UnnecesaryLoadElimination extends Pass5AsmOptimization {

   public Pass5UnnecesaryLoadElimination(Program program) {
      super(program);
   }

   @Override
   public boolean optimize() {
      AsmProgramStaticRegisterValues staticValues = new AsmProgramStaticRegisterValues(getAsmProgram(), getProgram());
      List<LineRef> allLines = flattenLines(getAsmProgram());
      Map<String, CanonicalResolver> scopeResolvers = buildScopeResolvers(allLines);
      boolean modified = false;

      for(int i = 0; i < allLines.size(); i++) {
         LineRef ref = allLines.get(i);
         if(!(ref.line instanceof AsmInstruction)) {
            continue;
         }
         AsmInstruction instruction = (AsmInstruction) ref.line;
         if(instruction.isNoOptimize() || instruction.getCpuOpcode() == null) {
            continue;
         }
         CpuOpcode cpuOpcode = instruction.getCpuOpcode();
         String mnem = cpuOpcode.getMnemonic();
         CpuAddressingMode mode = cpuOpcode.getAddressingMode();
         CanonicalResolver resolver = scopeResolvers.getOrDefault(ref.scope, scopeResolvers.get(""));

         if(mnem.equals("lda") && mode.equals(CpuAddressingMode.IMM)) {
            String immValue = instruction.getOperand1();
            AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
            if(instructionValues != null) {
               if(AsmProgramStaticRegisterValues.matchImm(instructionValues.getA(), immValue)) {
                  if(canRemoveRegister(allLines, i, "A", ref.scope)) {
                     removeLine(allLines, i, ref, instruction);
                     modified = true;
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
         } else if(mnem.equals("lda") && (mode.equals(CpuAddressingMode.ZP) || mode.equals(CpuAddressingMode.ABS))) {
            String memValue = instruction.getOperand1();
            if(!isVolatileOrHardwareOperand(memValue, ref.scope, resolver, getProgram())) {
               AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
               if(instructionValues != null) {
                  if(instructionValues.getaMem() != null && instructionValues.getaMem().equals(memValue)) {
                     if(canRemoveRegister(allLines, i, "A", ref.scope)) {
                        removeLine(allLines, i, ref, instruction);
                        modified = true;
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
            }
         } else if(mnem.equals("ldx") && mode.equals(CpuAddressingMode.IMM)) {
            String immValue = instruction.getOperand1();
            AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
            if(instructionValues != null) {
               if(AsmProgramStaticRegisterValues.matchImm(instructionValues.getX(), immValue)) {
                  if(canRemoveRegister(allLines, i, "X", ref.scope)) {
                     removeLine(allLines, i, ref, instruction);
                     modified = true;
                  }
               } else if(AsmProgramStaticRegisterValues.matchImm(instructionValues.getA(), immValue)) {
                  getLog().append("Replacing instruction " + instruction + " with TAX");
                  instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("tax", CpuAddressingMode.NON, false));
                  instruction.setOperand1(null);
               }
            }
         } else if(mnem.equals("ldx") && (mode.equals(CpuAddressingMode.ZP) || mode.equals(CpuAddressingMode.ABS))) {
            String memValue = instruction.getOperand1();
            if(!isVolatileOrHardwareOperand(memValue, ref.scope, resolver, getProgram())) {
               AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
               if(instructionValues != null) {
                  if(instructionValues.getxMem() != null && instructionValues.getxMem().equals(memValue)) {
                     if(canRemoveRegister(allLines, i, "X", ref.scope)) {
                        removeLine(allLines, i, ref, instruction);
                        modified = true;
                     }
                  } else if(instructionValues.getaMem() != null && instructionValues.getaMem().equals(memValue)) {
                     getLog().append("Replacing instruction " + instruction + " with TAX");
                     instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("tax", CpuAddressingMode.NON, false));
                     instruction.setOperand1(null);
                  }
               }
            }
         } else if(mnem.equals("ldy") && mode.equals(CpuAddressingMode.IMM)) {
            String immValue = instruction.getOperand1();
            AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
            if(instructionValues != null) {
               if(AsmProgramStaticRegisterValues.matchImm(instructionValues.getY(), immValue)) {
                  if(canRemoveRegister(allLines, i, "Y", ref.scope)) {
                     removeLine(allLines, i, ref, instruction);
                     modified = true;
                  }
               } else if(AsmProgramStaticRegisterValues.matchImm(instructionValues.getA(), immValue)) {
                  getLog().append("Replacing instruction " + instruction + " with TAY");
                  instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("tay", CpuAddressingMode.NON, false));
                  instruction.setOperand1(null);
               }
            }
         } else if(mnem.equals("ldy") && (mode.equals(CpuAddressingMode.ZP) || mode.equals(CpuAddressingMode.ABS))) {
            String memValue = instruction.getOperand1();
            if(!isVolatileOrHardwareOperand(memValue, ref.scope, resolver, getProgram())) {
               AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
               if(instructionValues != null) {
                  if(instructionValues.getyMem() != null && instructionValues.getyMem().equals(memValue)) {
                     if(canRemoveRegister(allLines, i, "Y", ref.scope)) {
                        removeLine(allLines, i, ref, instruction);
                        modified = true;
                     }
                  } else if(instructionValues.getaMem() != null && instructionValues.getaMem().equals(memValue)) {
                     getLog().append("Replacing instruction " + instruction + " with TAY");
                     instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("tay", CpuAddressingMode.NON, false));
                     instruction.setOperand1(null);
                  }
               }
            }
         } else if(mnem.equals("ldz") && mode.equals(CpuAddressingMode.IMM)) {
            String immValue = instruction.getOperand1();
            AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
            if(instructionValues != null) {
               if(AsmProgramStaticRegisterValues.matchImm(instructionValues.getZ(), immValue)) {
                  removeLine(allLines, i, ref, instruction);
                  modified = true;
               } else if(AsmProgramStaticRegisterValues.matchImm(instructionValues.getA(), immValue)) {
                  getLog().append("Replacing instruction " + instruction + " with TAZ");
                  instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("taz", CpuAddressingMode.NON, false));
                  instruction.setOperand1(null);
               }
            }
         } else if(mnem.equals("ldz") && (mode.equals(CpuAddressingMode.ZP) || mode.equals(CpuAddressingMode.ABS))) {
            String memValue = instruction.getOperand1();
            if(!isVolatileOrHardwareOperand(memValue, ref.scope, resolver, getProgram())) {
               AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
               if(instructionValues != null) {
                  if(instructionValues.getzMem() != null && instructionValues.getzMem().equals(memValue)) {
                     removeLine(allLines, i, ref, instruction);
                     modified = true;
                  } else if(instructionValues.getaMem() != null && instructionValues.getaMem().equals(memValue)) {
                     getLog().append("Replacing instruction " + instruction + " with TAZ");
                     instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("taz", CpuAddressingMode.NON, false));
                     instruction.setOperand1(null);
                  }
               }
            }
         } else if(mnem.equals("clc")) {
            AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
            if((instructionValues != null && Boolean.FALSE.equals(instructionValues.getFlagC()))
               || !isFlagCLiveAfter(allLines, i, ref.scope, getProgram())) {
               removeLine(allLines, i, ref, instruction);
               modified = true;
            }
         } else if(mnem.equals("sec")) {
            AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
            if((instructionValues != null && Boolean.TRUE.equals(instructionValues.getFlagC()))
               || !isFlagCLiveAfter(allLines, i, ref.scope, getProgram())) {
               removeLine(allLines, i, ref, instruction);
               modified = true;
            }
         } else if(mnem.equals("cmp") || mnem.equals("cpx") || mnem.equals("cpy")) {
            if(!areModifiedFlagsLiveAfter(allLines, i, ref.scope, getProgram())
               && !isVolatileOrHardwareOperand(instruction.getOperand1(), ref.scope, resolver, getProgram())) {
               removeLine(allLines, i, ref, instruction);
               modified = true;
            }
         } else if(mnem.equals("tsx")) {
            AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
            if(instructionValues != null && Boolean.TRUE.equals(instructionValues.isXIsSp())) {
               if(canRemoveRegister(allLines, i, "X", ref.scope)) {
                  removeLine(allLines, i, ref, instruction);
                  modified = true;
               }
            }
         }
      }
      return modified;
   }

   private void removeLine(List<LineRef> allLines, int idx, LineRef ref, AsmInstruction instruction) {
      getLog().append("Removing instruction " + instruction);
      ref.chunk.getLines().remove(instruction);
      // Replace entry in flattened list with a harmless comment so backwards flag scans skip it
      allLines.set(idx, new LineRef(ref.index, ref.chunk, ref.indexInChunk, new AsmComment("", false), ref.scope));
   }

   private boolean canRemoveRegister(List<LineRef> allLines, int currentIdx, String reg, String currentScope) {
      return !areModifiedFlagsLiveAfter(allLines, currentIdx, currentScope, getProgram())
         || areFlagsEstablishedForReg(allLines, currentIdx, reg, currentScope);
   }
}
