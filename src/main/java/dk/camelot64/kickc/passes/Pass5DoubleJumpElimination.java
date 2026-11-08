package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optimize assembler code by removing jumps to labels immediately following the jump
 */
public class Pass5DoubleJumpElimination extends Pass5AsmOptimization {

   public Pass5DoubleJumpElimination(Program program) {
      super(program);
   }

   public boolean optimize() {
      boolean optimized = false;

      // Find all labels immediately followed by a jump or rts
      String currentScope = "";
      List<String> currentLabels = new ArrayList<>();
      Map<String, String> immediateJumps = new LinkedHashMap<>();
      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         for(AsmLine line : chunk.getLines()) {
            if(line instanceof AsmScopeBegin) {
               currentScope = ((AsmScopeBegin) line).getLabel();
               currentLabels.clear();
            } else if(line instanceof AsmScopeEnd) {
               currentScope = "";
               currentLabels.clear();
            } else if(line instanceof AsmLabel) {
               currentLabels.add(((AsmLabel) line).getLabel());
            } else if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl) {
               // ignore
            } else if(line instanceof AsmBasicUpstart || line instanceof AsmDataNumeric || line instanceof AsmDataZeroFill || line instanceof AsmDataString || line instanceof AsmDataAlignment || line instanceof AsmLoopAlign || line instanceof AsmSetPc || line instanceof AsmInlineKickAsm|| line instanceof AsmSetEncoding|| line instanceof AsmSetCpu|| line instanceof AsmDataKickAsm|| line instanceof AsmSegmentDef|| line instanceof AsmSegment|| line instanceof AsmFile) {
               currentLabels.clear();
            } else if(line instanceof AsmInstruction) {
               if(!currentLabels.isEmpty()) {
                  AsmInstruction asmInstruction = (AsmInstruction) line;
                  CpuOpcode jmpOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("jmp", CpuAddressingMode.ABS, false);
                  CpuOpcode rtsOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("rts", CpuAddressingMode.NON, false);
                  if(asmInstruction.getCpuOpcode().equals(jmpOpcode)) {
                     for(String lbl : currentLabels) {
                        immediateJumps.put(currentScope + "::" + lbl, asmInstruction.getOperand1());
                     }
                  }
                  if(asmInstruction.getCpuOpcode().equals(rtsOpcode)) {
                     for(String lbl : currentLabels) {
                        immediateJumps.put(currentScope + "::" + lbl, "rts");
                     }
                  }
               }
               currentLabels.clear();
            } else {
               throw new RuntimeException("ASM not handled " + line);
            }
         }
      }

      // Look through the code for double-jumps
      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         for(AsmLine line : chunk.getLines()) {
            if(line instanceof AsmScopeBegin) {
               currentScope = ((AsmScopeBegin) line).getLabel();
            } else if(line instanceof AsmScopeEnd) {
               currentScope = "";
            } else if(line instanceof AsmInstruction) {
               AsmInstruction asmInstruction = (AsmInstruction) line;
               if(asmInstruction.getCpuOpcode().isJump()) {
                  String jumpTarget = immediateJumps.get(currentScope + "::" + asmInstruction.getOperandJumpTarget());
                  boolean isUnconditionalJmp = "jmp".equals(asmInstruction.getCpuOpcode().getMnemonic());
                  if("rts".equals(jumpTarget) && isUnconditionalJmp) {
                     getLog().append("Replacing jump to rts with rts in " + asmInstruction.toString());
                     CpuOpcode rtsOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("rts", CpuAddressingMode.NON, false);
                     asmInstruction.setCpuOpcode(rtsOpcode);
                     asmInstruction.setOperand1(null);
                     asmInstruction.setOperand2(null);
                     optimized = true;
                  } else if(jumpTarget != null && !"rts".equals(jumpTarget) && !jumpTarget.equals(asmInstruction.getOperandJumpTarget())) {
                     // Do not redirect 2-byte conditional relative branches to tail-called procedure names,
                     // as cross-procedure targets exceed 8-bit branch range and expand into 5-byte long branches.
                     if(isUnconditionalJmp || jumpTarget.startsWith("__b")) {
                        getLog().append("Skipping double jump to " + jumpTarget + " in " + asmInstruction.toString());
                        asmInstruction.setOperandJumpTarget(jumpTarget);
                        optimized = true;
                     }
                  }
               }
            }
         }
      }

      return optimized;
   }
}
