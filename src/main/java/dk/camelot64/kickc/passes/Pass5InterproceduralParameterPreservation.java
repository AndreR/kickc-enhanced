package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;

import java.util.*;

/**
 * Pass 5 Optimization: Interprocedural Preservation of Unmodified Zero-Page Parameters across JSR.
 *
 * When a caller calls a subroutine (JSR proc), many zero-page parameters and variables remain
 * completely unmodified by the callee (read-only parameters, or unrelated zero-page locations).
 *
 * This pass performs call-graph transitive closure analysis over all procedures in AsmProgram:
 * 1. Computes the exact set of zero-page addresses modified by each procedure (and all its transitive callees).
 * 2. Tracks known immediate values in zero-page locations across JSR calls.
 * 3. Eliminates subsequent redundant stores (STA.Z, STX.Z, STY.Z) if the destination zero-page location
 *    is guaranteed to already contain the identical immediate value.
 */
public class Pass5InterproceduralParameterPreservation extends Pass5AsmOptimization {

   private static final Set<String> ZP_MODIFY_MNEMONICS = new HashSet<>(Arrays.asList(
         "sta", "stx", "sty", "sax", "asl", "lsr", "rol", "ror", "inc", "dec", "dcp", "isc"
   ));

   public Pass5InterproceduralParameterPreservation(Program program) {
      super(program);
   }

   private static class ProcedureInfo {
      final String name;
      final Set<String> directZpWrites = new HashSet<>();
      final Set<String> callees = new HashSet<>();
      boolean hasUnknownWrites = false;

      ProcedureInfo(String name) {
         this.name = name;
      }
   }

   @Override
   public boolean optimize() {
      boolean modified = false;

      // 1. Collect all procedures and their direct writes & callees
      Map<String, ProcedureInfo> procMap = new HashMap<>();
      ProcedureInfo currentProc = null;

      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         for(AsmLine line : chunk.getLines()) {
            if(line instanceof AsmScopeBegin) {
               String name = ((AsmScopeBegin) line).getLabel();
               currentProc = new ProcedureInfo(name);
               procMap.put(name, currentProc);
            } else if(line instanceof AsmScopeEnd) {
               currentProc = null;
            } else if(currentProc != null) {
               if(line instanceof AsmInlineKickAsm) {
                  currentProc.hasUnknownWrites = true;
               } else if(line instanceof AsmInstruction) {
                  AsmInstruction inst = (AsmInstruction) line;
                  CpuOpcode opcode = inst.getCpuOpcode();
                  if(opcode != null) {
                     String mnem = opcode.getMnemonic();
                     CpuAddressingMode mode = opcode.getAddressingMode();
                     if("jsr".equals(mnem)) {
                        String target = inst.getOperand1();
                        if(target != null && !target.startsWith("!")) {
                           currentProc.callees.add(target.trim());
                        } else {
                           currentProc.hasUnknownWrites = true;
                        }
                     } else if(ZP_MODIFY_MNEMONICS.contains(mnem)) {
                        if(mode == CpuAddressingMode.ZP || mode == CpuAddressingMode.ZPX || mode == CpuAddressingMode.ZPY) {
                           String op = inst.getOperand1();
                           if(op != null) {
                              currentProc.directZpWrites.add(op.trim());
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      // 2. Transitive closure fixpoint of writes across call graph
      Map<String, Set<String>> totalZpWrites = new HashMap<>();
      Map<String, Boolean> totalUnknownWrites = new HashMap<>();

      for(Map.Entry<String, ProcedureInfo> entry : procMap.entrySet()) {
         totalZpWrites.put(entry.getKey(), new HashSet<>(entry.getValue().directZpWrites));
         totalUnknownWrites.put(entry.getKey(), entry.getValue().hasUnknownWrites);
      }

      boolean changed = true;
      while(changed) {
         changed = false;
         for(Map.Entry<String, ProcedureInfo> entry : procMap.entrySet()) {
            String procName = entry.getKey();
            Set<String> writes = totalZpWrites.get(procName);
            boolean unknown = totalUnknownWrites.get(procName);

            for(String callee : entry.getValue().callees) {
               ProcedureInfo calleeInfo = procMap.get(callee);
               if(calleeInfo != null) {
                  if(Boolean.TRUE.equals(totalUnknownWrites.get(callee)) && !unknown) {
                     totalUnknownWrites.put(procName, true);
                     unknown = true;
                     changed = true;
                  }
                  Set<String> calleeWrites = totalZpWrites.get(callee);
                  if(calleeWrites != null) {
                     int before = writes.size();
                     writes.addAll(calleeWrites);
                     if(writes.size() > before) {
                        changed = true;
                     }
                  }
               } else {
                  // Unknown external callee
                  if(!unknown) {
                     totalUnknownWrites.put(procName, true);
                     unknown = true;
                     changed = true;
                  }
               }
            }
         }
      }

      // 3. Scan each chunk for redundant immediate stores across JSR
      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         Map<String, String> knownZp = new HashMap<>();
         String currentValA = null;
         String currentValX = null;
         String currentValY = null;

         ListIterator<AsmLine> it = chunk.getLines().listIterator();
         while(it.hasNext()) {
            AsmLine line = it.next();

            if(line instanceof AsmScopeBegin || line instanceof AsmScopeEnd ||
               line instanceof AsmLabel || line instanceof AsmInlineKickAsm) {
               knownZp.clear();
               currentValA = null;
               currentValX = null;
               currentValY = null;
               continue;
            }

            if(line instanceof AsmInstruction) {
               AsmInstruction inst = (AsmInstruction) line;
               if(inst.isNoOptimize() || inst.getCpuOpcode() == null) {
                  knownZp.clear();
                  currentValA = null;
                  currentValX = null;
                  currentValY = null;
                  continue;
               }

               CpuOpcode opcode = inst.getCpuOpcode();
               String mnem = opcode.getMnemonic();
               CpuAddressingMode mode = opcode.getAddressingMode();
               String operand = inst.getOperand1();

               // Control flow breaks
               if("jmp".equals(mnem) || "rts".equals(mnem) || "rti".equals(mnem)) {
                  knownZp.clear();
                  currentValA = null;
                  currentValX = null;
                  currentValY = null;
                  continue;
               }

               // Conditional branches and jumps reset register values and invalidate paths
               if(opcode.isJump()) {
                  knownZp.clear();
                  currentValA = null;
                  currentValX = null;
                  currentValY = null;
                  continue;
               }

               // Subroutine call (JSR)
               if("jsr".equals(mnem)) {
                  currentValA = null;
                  currentValX = null;
                  currentValY = null;
                  String callee = (operand != null) ? operand.trim() : null;
                  if(callee != null && procMap.containsKey(callee) && !Boolean.TRUE.equals(totalUnknownWrites.get(callee))) {
                     Set<String> writes = totalZpWrites.get(callee);
                     if(writes != null) {
                        for(String w : writes) {
                           knownZp.remove(w);
                        }
                     }
                  } else {
                     knownZp.clear();
                  }
                  continue;
               }

               // Loads of immediate values into registers
               if(mode == CpuAddressingMode.IMM && operand != null && operand.startsWith("#")) {
                  if("lda".equals(mnem)) {
                     currentValA = operand;
                     continue;
                  } else if("ldx".equals(mnem)) {
                     currentValX = operand;
                     continue;
                  } else if("ldy".equals(mnem)) {
                     currentValY = operand;
                     continue;
                  }
               }

               // Register overwrites from memory or other sources
               if("lda".equals(mnem) || "pla".equals(mnem) || "txa".equals(mnem) || "tya".equals(mnem) || "lax".equals(mnem)) {
                  currentValA = null;
               }
               if("ldx".equals(mnem) || "plx".equals(mnem) || "tax".equals(mnem) || "tsx".equals(mnem) || "lax".equals(mnem)) {
                  currentValX = null;
               }
               if("ldy".equals(mnem) || "ply".equals(mnem) || "tay".equals(mnem)) {
                  currentValY = null;
               }
               if("adc".equals(mnem) || "sbc".equals(mnem) || "and".equals(mnem) || "ora".equals(mnem) || "eor".equals(mnem) ||
                  "asl".equals(mnem) || "lsr".equals(mnem) || "rol".equals(mnem) || "ror".equals(mnem)) {
                  if(mode != CpuAddressingMode.ZP && mode != CpuAddressingMode.ABS) {
                     currentValA = null;
                  }
               }
               if("inx".equals(mnem) || "dex".equals(mnem)) currentValX = null;
               if("iny".equals(mnem) || "dey".equals(mnem)) currentValY = null;

               // Stores into Zero-Page
               if(mode == CpuAddressingMode.ZP && operand != null && !Pass5RedundantStoreAndReloadElimination.isHardwareAddress(operand)) {
                  String target = operand.trim();
                  String storedVal = null;
                  if("sta".equals(mnem)) storedVal = currentValA;
                  else if("stx".equals(mnem)) storedVal = currentValX;
                  else if("sty".equals(mnem)) storedVal = currentValY;

                  if(storedVal != null) {
                     if(storedVal.equals(knownZp.get(target))) {
                        // REDUNDANT STORE IDENTIFIED!
                        getLog().append("Pass 5: Eliminating redundant store across JSR: " + inst.getAsm());
                        it.remove();
                        modified = true;
                        continue;
                     } else {
                        knownZp.put(target, storedVal);
                     }
                  } else {
                     knownZp.remove(target);
                  }
               } else if(ZP_MODIFY_MNEMONICS.contains(mnem) && operand != null) {
                  // Any other instruction that modifies ZP memory invalidates that ZP location
                  knownZp.remove(operand.trim());
               }
            }
         }
      }

      return modified;
   }
}
