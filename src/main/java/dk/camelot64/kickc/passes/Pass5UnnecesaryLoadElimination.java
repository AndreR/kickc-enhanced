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
      AsmProgramStaticRegisterValues staticValues = new AsmProgramStaticRegisterValues(getAsmProgram(), getProgram(), getRoundContext());
      List<LineRef> allLines = staticValues.getAllLines();
      Map<String, CanonicalResolver> scopeResolvers = staticValues.getScopeResolvers();
      CanonicalResolver defaultResolver = scopeResolvers.get("");
      boolean modified = false;

      for(int i = 0; i < allLines.size(); i++) {
         LineRef ref = allLines.get(i);
         if(!isScopeDirty(ref.scope)) {
            continue;
         }
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
                  replaceWithInherent(instruction, "txa", ref.scope);
               } else if(AsmProgramStaticRegisterValues.matchImm(instructionValues.getY(), immValue)) {
                  replaceWithInherent(instruction, "tya", ref.scope);
               } else if(AsmProgramStaticRegisterValues.matchImm(instructionValues.getZ(), immValue)) {
                  replaceWithInherent(instruction, "tza", ref.scope);
               }
            }
         } else if(mnem.equals("lda") && (mode.equals(CpuAddressingMode.ZP) || mode.equals(CpuAddressingMode.ABS))) {
            String memValue = instruction.getOperand1();
            CanonicalResolver resolver = scopeResolvers.getOrDefault(ref.scope, defaultResolver);
            if(!isVolatileOrHardwareOperand(memValue, ref.scope, resolver, getProgram())) {
               AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
               if(instructionValues != null) {
                  if(instructionValues.getaMem() != null && instructionValues.getaMem().equals(memValue)) {
                     if(canRemoveRegister(allLines, i, "A", ref.scope)) {
                        removeLine(allLines, i, ref, instruction);
                        modified = true;
                     }
                  } else if(instructionValues.getxMem() != null && instructionValues.getxMem().equals(memValue)) {
                     replaceWithInherent(instruction, "txa", ref.scope);
                  } else if(instructionValues.getyMem() != null && instructionValues.getyMem().equals(memValue)) {
                     replaceWithInherent(instruction, "tya", ref.scope);
                  } else if(instructionValues.getzMem() != null && instructionValues.getzMem().equals(memValue)) {
                     replaceWithInherent(instruction, "tza", ref.scope);
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
                  replaceWithInherent(instruction, "tax", ref.scope);
                  modified = true;
               } else {
                  // ZP-02: Strength-reduce LDX #(k±1) -> INX / DEX (saves 1 byte, identical N/Z/C/V)
                  Integer prevX = AsmProgramStaticRegisterValues.getImmValue(instructionValues.getX());
                  Integer targetX = AsmProgramStaticRegisterValues.getImmValue(immValue);
                  if(prevX != null && targetX != null) {
                     if(((prevX + 1) & 0xff) == targetX) {
                        replaceWithInherent(instruction, "inx", ref.scope);
                        modified = true;
                     } else if(((prevX - 1) & 0xff) == targetX) {
                        replaceWithInherent(instruction, "dex", ref.scope);
                        modified = true;
                     }
                  }
               }
            }
         } else if(mnem.equals("ldx") && (mode.equals(CpuAddressingMode.ZP) || mode.equals(CpuAddressingMode.ABS))) {
            String memValue = instruction.getOperand1();
            CanonicalResolver resolver = scopeResolvers.getOrDefault(ref.scope, defaultResolver);
            if(!isVolatileOrHardwareOperand(memValue, ref.scope, resolver, getProgram())) {
               AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
               if(instructionValues != null) {
                  if(instructionValues.getxMem() != null && instructionValues.getxMem().equals(memValue)) {
                     if(canRemoveRegister(allLines, i, "X", ref.scope)) {
                        removeLine(allLines, i, ref, instruction);
                        modified = true;
                     }
                  } else if(instructionValues.getaMem() != null && instructionValues.getaMem().equals(memValue)) {
                     replaceWithInherent(instruction, "tax", ref.scope);
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
                  replaceWithInherent(instruction, "tay", ref.scope);
                  modified = true;
               } else {
                  // ZP-02: Strength-reduce LDY #(k±1) -> INY / DEY (saves 1 byte, identical N/Z/C/V)
                  Integer prevY = AsmProgramStaticRegisterValues.getImmValue(instructionValues.getY());
                  Integer targetY = AsmProgramStaticRegisterValues.getImmValue(immValue);
                  if(prevY != null && targetY != null) {
                     if(((prevY + 1) & 0xff) == targetY) {
                        replaceWithInherent(instruction, "iny", ref.scope);
                        modified = true;
                     } else if(((prevY - 1) & 0xff) == targetY) {
                        replaceWithInherent(instruction, "dey", ref.scope);
                        modified = true;
                     }
                  }
               }
            }
         } else if(mnem.equals("ldy") && (mode.equals(CpuAddressingMode.ZP) || mode.equals(CpuAddressingMode.ABS))) {
            String memValue = instruction.getOperand1();
            CanonicalResolver resolver = scopeResolvers.getOrDefault(ref.scope, defaultResolver);
            if(!isVolatileOrHardwareOperand(memValue, ref.scope, resolver, getProgram())) {
               AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
               if(instructionValues != null) {
                  if(instructionValues.getyMem() != null && instructionValues.getyMem().equals(memValue)) {
                     if(canRemoveRegister(allLines, i, "Y", ref.scope)) {
                        removeLine(allLines, i, ref, instruction);
                        modified = true;
                     }
                  } else if(instructionValues.getaMem() != null && instructionValues.getaMem().equals(memValue)) {
                     replaceWithInherent(instruction, "tay", ref.scope);
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
                  replaceWithInherent(instruction, "taz", ref.scope);
               }
            }
         } else if(mnem.equals("ldz") && (mode.equals(CpuAddressingMode.ZP) || mode.equals(CpuAddressingMode.ABS))) {
            String memValue = instruction.getOperand1();
            CanonicalResolver resolver = scopeResolvers.getOrDefault(ref.scope, defaultResolver);
            if(!isVolatileOrHardwareOperand(memValue, ref.scope, resolver, getProgram())) {
               AsmProgramStaticRegisterValues.AsmRegisterValues instructionValues = staticValues.getValues(instruction);
               if(instructionValues != null) {
                  if(instructionValues.getzMem() != null && instructionValues.getzMem().equals(memValue)) {
                     removeLine(allLines, i, ref, instruction);
                     modified = true;
                  } else if(instructionValues.getaMem() != null && instructionValues.getaMem().equals(memValue)) {
                     replaceWithInherent(instruction, "taz", ref.scope);
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
               && !isVolatileOrHardwareOperand(instruction, ref.scope, scopeResolvers.getOrDefault(ref.scope, defaultResolver), getProgram())) {
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

   private void replaceWithInherent(AsmInstruction instruction, String targetMnem, String scope) {
      getLog().append("Replacing instruction " + instruction + " with " + targetMnem.toUpperCase());
      instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode(targetMnem, CpuAddressingMode.NON, false));
      instruction.setOperand1(null);
      markScopeDirty(scope);
   }

   private void removeLine(List<LineRef> allLines, int idx, LineRef ref, AsmInstruction instruction) {
      getLog().append("Removing instruction " + instruction);
      tombstoneLine(allLines, idx);
      markScopeDirty(ref.scope);
   }

   private boolean canRemoveRegister(List<LineRef> allLines, int currentIdx, String reg, String currentScope) {
      return !areModifiedFlagsLiveAfter(allLines, currentIdx, currentScope, getProgram())
         || areFlagsEstablishedForReg(allLines, currentIdx, reg, currentScope);
   }
}
