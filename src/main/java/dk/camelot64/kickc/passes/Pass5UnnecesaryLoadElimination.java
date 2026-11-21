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
                  modified = true;
               } else {
                  // ZP-02: Strength-reduce LDX #(k±1) -> INX / DEX (saves 1 byte, identical N/Z/C/V)
                  Integer prevX = AsmProgramStaticRegisterValues.getImmValue(instructionValues.getX());
                  Integer targetX = AsmProgramStaticRegisterValues.getImmValue(immValue);
                  if(prevX != null && targetX != null) {
                     if(((prevX + 1) & 0xff) == targetX) {
                        getLog().append("Replacing instruction " + instruction + " with INX");
                        instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("inx", CpuAddressingMode.NON, false));
                        instruction.setOperand1(null);
                        modified = true;
                     } else if(((prevX - 1) & 0xff) == targetX) {
                        getLog().append("Replacing instruction " + instruction + " with DEX");
                        instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("dex", CpuAddressingMode.NON, false));
                        instruction.setOperand1(null);
                        modified = true;
                     }
                  }
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
                  modified = true;
               } else {
                  // ZP-02: Strength-reduce LDY #(k±1) -> INY / DEY (saves 1 byte, identical N/Z/C/V)
                  Integer prevY = AsmProgramStaticRegisterValues.getImmValue(instructionValues.getY());
                  Integer targetY = AsmProgramStaticRegisterValues.getImmValue(immValue);
                  if(prevY != null && targetY != null) {
                     if(((prevY + 1) & 0xff) == targetY) {
                        getLog().append("Replacing instruction " + instruction + " with INY");
                        instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("iny", CpuAddressingMode.NON, false));
                        instruction.setOperand1(null);
                        modified = true;
                     } else if(((prevY - 1) & 0xff) == targetY) {
                        getLog().append("Replacing instruction " + instruction + " with DEY");
                        instruction.setCpuOpcode(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("dey", CpuAddressingMode.NON, false));
                        instruction.setOperand1(null);
                        modified = true;
                     }
                  }
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
            } else if((mnem.equals("cpx") || mnem.equals("cpy")) && mode.equals(CpuAddressingMode.IMM)
                  && ("0".equals(instruction.getOperand1()) || "$0".equals(instruction.getOperand1()) || "$00".equals(instruction.getOperand1()))
                  && !isRegALiveAfter(allLines, i, ref.scope)
                  && !isFlagCLiveAfter(allLines, i, ref.scope, getProgram())) {
               String transferMnem = mnem.equals("cpx") ? "txa" : "tya";
               CpuOpcode transferOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode(transferMnem, CpuAddressingMode.NON, false);
               if(transferOp != null) {
                  getLog().append("Replacing " + instruction + " with " + transferMnem.toUpperCase());
                  instruction.setCpuOpcode(transferOp);
                  instruction.setOperand1(null);
                  modified = true;
               }
            }
         } else if(mnem.equals("txa") || mnem.equals("tya")) {
            int nextIdx = findNextInstruction(allLines, i + 1);
            if(nextIdx != -1) {
               LineRef nextRef = allLines.get(nextIdx);
               AsmInstruction nextInst = (AsmInstruction) nextRef.line;
               if(nextInst.getCpuOpcode() != null && nextInst.getCpuOpcode().getMnemonic().equals("sta")) {
                  CpuAddressingMode nextMode = nextInst.getCpuOpcode().getAddressingMode();
                  String storeMnem = mnem.equals("txa") ? "stx" : "sty";
                  CpuOpcode storeOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode(storeMnem, nextMode, false);
                  if(storeOp != null
                        && !isRegALiveAfter(allLines, nextIdx, ref.scope)
                        && (!areModifiedFlagsLiveAfter(allLines, nextIdx, ref.scope, getProgram())
                              || areFlagsEstablishedForReg(allLines, i, mnem.equals("txa") ? "X" : "Y", ref.scope))) {
                     getLog().append("Folding " + instruction + " ; " + nextInst + " -> " + storeMnem.toUpperCase());
                     nextInst.setCpuOpcode(storeOp);
                     removeLine(allLines, i, ref, instruction);
                     modified = true;
                  }
               }
            }
         } else if(mnem.equals("lda")) {
            int nextIdx = findNextInstruction(allLines, i + 1);
            if(nextIdx != -1) {
               LineRef nextRef = allLines.get(nextIdx);
               AsmInstruction nextInst = (AsmInstruction) nextRef.line;
               String nextMnem = nextInst.getCpuOpcode() != null ? nextInst.getCpuOpcode().getMnemonic() : "";
               if((nextMnem.equals("tay") || (nextMnem.equals("tax") && mode.equals(CpuAddressingMode.IMM)))
                     && !isRegALiveAfter(allLines, nextIdx, ref.scope)) {
                  String loadMnem = nextMnem.equals("tay") ? "ldy" : "ldx";
                  CpuOpcode loadOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode(loadMnem, mode, false);
                  if(loadOp != null) {
                     getLog().append("Folding " + instruction + " ; " + nextInst + " -> " + loadMnem.toUpperCase());
                     instruction.setCpuOpcode(loadOp);
                     removeLine(allLines, nextIdx, nextRef, nextInst);
                     modified = true;
                  }
               }
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

   private boolean isRegALiveAfter(List<LineRef> allLines, int currentIdx, String currentScope) {
      for(int k = currentIdx + 1; k < allLines.size(); k++) {
         LineRef lr = allLines.get(k);
         if(lr.scope != null && !lr.scope.equals(currentScope)) return true;
         if(lr.line instanceof AsmLabel || lr.line instanceof AsmScopeBegin || lr.line instanceof AsmScopeEnd) return true;
         if(lr.line instanceof AsmInstruction inst && inst.getCpuOpcode() != null) {
            String m = inst.getCpuOpcode().getMnemonic();
            if(m.equals("lda") || m.equals("pla") || m.equals("txa") || m.equals("tya") || m.equals("tza")) {
               return false; // A overwritten before read
            }
            if(m.equals("rts") || m.equals("rti") || m.equals("jmp") || m.equals("jsr") || inst.getCpuOpcode().isJump()) {
               return true;
            }
            if(m.equals("sta") || m.equals("tax") || m.equals("tay") || m.equals("taz") || m.equals("pha")
                  || m.equals("cmp") || m.equals("adc") || m.equals("sbc") || m.equals("and")
                  || m.equals("ora") || m.equals("eor") || m.equals("bit")) {
               return true; // A is read
            }
            if((m.equals("asl") || m.equals("lsr") || m.equals("rol") || m.equals("ror"))
                  && inst.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.NON)) {
               return true;
            }
         }
      }
      return true;
   }
}
