package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.Registers;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;

import java.util.*;

/**
 * GEN-06: Auto-A-Register Return ABI optimization for 8-bit procedures whose return variable
 * remained in zero page after Pass 4 register allocation.
 *
 * Callee:  ... ; sta.z return ; rts   ->   ... ; rts   (or stx/sty return -> txa/tya)
 * Caller:  jsr fn ; lda.z fn.return   ->   jsr fn
 */
public class Pass5ARegisterReturnOptimization extends Pass5AsmOptimization {

   public Pass5ARegisterReturnOptimization(Program program) {
      super(program);
   }

   @Override
   public boolean optimize() {
      if(getProgram().getScope() == null) return false;
      List<LineRef> allLines = flattenLines(getAsmProgram());
      if(allLines.isEmpty()) return false;

      // Map procedure name -> scope range in allLines
      Map<String, int[]> scopeRanges = new LinkedHashMap<>();
      int scopeStart = -1;
      String curScope = null;
      for(int i = 0; i < allLines.size(); i++) {
         AsmLine l = allLines.get(i).line;
         if(l instanceof AsmScopeBegin sb) {
            curScope = sb.getLabel();
            scopeStart = i;
         } else if(l instanceof AsmScopeEnd && curScope != null) {
            scopeRanges.put(curScope, new int[]{scopeStart, i});
            curScope = null;
            scopeStart = -1;
         }
      }

      boolean modified = false;
      for(Procedure proc : getProgram().getScope().getAllProcedures(true)) {
         if(proc == null) continue;
         String procName = proc.getLocalName();
         if("main".equals(procName) || "__start".equals(procName)) continue;
         if(proc.getInterruptType() != null) continue;
         if(proc.getCallingConvention() == Procedure.CallingConvention.STACK_CALL) continue;
         if(!SymbolType.BYTE.equals(proc.getReturnType()) && !SymbolType.SBYTE.equals(proc.getReturnType())) continue;
         if(Pass2ConstantIdentification.isAddressOfUsed(proc.getRef(), getProgram())) continue;

         Variable retVar = proc.getLocalVariable("return");
         if(retVar == null || !(retVar.getAllocation() instanceof Registers.RegisterZpMem)) continue;

         int[] range = scopeRanges.get(procName);
         if(range == null) continue;

         String retAsmName = retVar.getAsmName() != null ? retVar.getAsmName() : "return";
         String qualifiedRetName = procName + "." + retAsmName;

         // 1. Verify callee: every RTS must be immediately preceded (without crossing labels/branches) by sta/stx/sty return
         List<Integer> calleeStoreIndices = new ArrayList<>();
         boolean calleeOk = true;
         int rtsCount = 0;
         for(int i = range[0] + 1; i < range[1]; i++) {
            AsmLine l = allLines.get(i).line;
            if(l instanceof AsmInlineKickAsm) {
               calleeOk = false;
               break;
            }
            if(l instanceof AsmInstruction inst && inst.getCpuOpcode() != null) {
               if("rts".equals(inst.getCpuOpcode().getMnemonic())) {
                  rtsCount++;
                  int prevIdx = findPrevInstruction(allLines, i - 1);
                  if(prevIdx <= range[0]) {
                     calleeOk = false;
                     break;
                  }
                  // Verify no AsmLabel between prevIdx and i (CF-09 multi-path safety)
                  boolean labelBetween = false;
                  for(int k = prevIdx + 1; k < i; k++) {
                     if(allLines.get(k).line instanceof AsmLabel) {
                        labelBetween = true;
                        break;
                     }
                  }
                  if(labelBetween) {
                     calleeOk = false;
                     break;
                  }
                  AsmInstruction prevInst = (AsmInstruction) allLines.get(prevIdx).line;
                  if(prevInst.isNoOptimize() || prevInst.getCpuOpcode() == null) {
                     calleeOk = false;
                     break;
                  }
                  String pm = prevInst.getCpuOpcode().getMnemonic();
                  String pop = prevInst.getOperand1();
                  if((pm.equals("sta") || pm.equals("stx") || pm.equals("sty")) &&
                     prevInst.getCpuOpcode().getAddressingMode() == CpuAddressingMode.ZP &&
                     pop != null && (pop.equals(retAsmName) || pop.equals(qualifiedRetName))) {
                     calleeStoreIndices.add(prevIdx);
                  } else {
                     calleeOk = false;
                     break;
                  }
               }
            }
         }
         if(!calleeOk || rtsCount == 0) continue;

         // Ensure callee itself does not read retAsmName anywhere
         for(int i = range[0] + 1; i < range[1]; i++) {
            if(calleeStoreIndices.contains(i)) continue;
            AsmLine l = allLines.get(i).line;
            if(l instanceof AsmInstruction inst && inst.getOperand1() != null) {
               String op = inst.getOperand1().trim();
               if(op.equals(retAsmName) || op.equals(qualifiedRetName)) {
                  calleeOk = false;
                  break;
               }
            }
         }
         if(!calleeOk) continue;

         // 2. Verify all callers: every jsr procName must be followed by lda.z procName.return (or A dead & no read of return)
         List<Integer> callerLoadIndices = new ArrayList<>();
         boolean callersOk = true;
         int callCount = 0;
         for(int i = 0; i < allLines.size(); i++) {
            if(i >= range[0] && i <= range[1]) continue;
            AsmLine l = allLines.get(i).line;
            if(l instanceof AsmInstruction inst && inst.getCpuOpcode() != null) {
               String m = inst.getCpuOpcode().getMnemonic();
               String op = inst.getOperand1();
               if("jsr".equals(m) && procName.equals(op)) {
                  callCount++;
                  int nextIdx = findNextInstruction(allLines, i + 1);
                  if(nextIdx != -1) {
                     AsmInstruction nextInst = (AsmInstruction) allLines.get(nextIdx).line;
                     if(nextInst.getCpuOpcode() != null && !nextInst.isNoOptimize() &&
                        "lda".equals(nextInst.getCpuOpcode().getMnemonic()) &&
                        nextInst.getCpuOpcode().getAddressingMode() == CpuAddressingMode.ZP &&
                        qualifiedRetName.equals(nextInst.getOperand1())) {
                        callerLoadIndices.add(nextIdx);
                        continue;
                     }
                  }
                  // If not followed by lda.z procName.return, only safe if A is dead after jsr
                  if(!isRegDeadAlongPath("A", allLines, i + 1, new HashSet<>(), allLines.get(i).scope, getProgram())) {
                     callersOk = false;
                     break;
                  }
               } else if(op != null && op.trim().equals(qualifiedRetName)) {
                  // Any other reference to procName.return outside the immediate post-jsr lda
                  if(!callerLoadIndices.contains(i)) {
                     callersOk = false;
                     break;
                  }
               }
            }
         }
         if(!callersOk || callCount == 0 || callerLoadIndices.isEmpty()) continue;

         // 3. Apply transformation: callee stores -> removed (or txa/tya), caller loads -> removed
         CpuOpcode txaOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("txa", CpuAddressingMode.NON, false);
         CpuOpcode tyaOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("tya", CpuAddressingMode.NON, false);
         for(int storeIdx : calleeStoreIndices) {
            AsmInstruction storeInst = (AsmInstruction) allLines.get(storeIdx).line;
            String sm = storeInst.getCpuOpcode().getMnemonic();
            if("sta".equals(sm)) {
               tombstoneLine(allLines, storeIdx);
            } else if("stx".equals(sm) && txaOp != null) {
               storeInst.setCpuOpcode(txaOp);
               storeInst.setOperand1(null);
            } else if("sty".equals(sm) && tyaOp != null) {
               storeInst.setCpuOpcode(tyaOp);
               storeInst.setOperand1(null);
            }
         }
         for(int loadIdx : callerLoadIndices) {
            tombstoneLine(allLines, loadIdx);
         }
         getLog().append("GEN-06: Optimized 8-bit return of " + procName + " to A register (" + callerLoadIndices.size() + " call sites)");
         modified = true;
      }

      return modified;
   }
}
