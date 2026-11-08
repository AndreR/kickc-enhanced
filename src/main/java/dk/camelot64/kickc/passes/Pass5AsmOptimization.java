package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.CompileLog;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;

import java.util.*;

/**
 * Optimization performed on Assembler Code (Asm Code).
 * Optimizations are performed repeatedly until none of them yield any result
 **/
public abstract class Pass5AsmOptimization {

   public static class LineRef {
      public final AsmChunk chunk;
      public final AsmLine line;

      public LineRef(AsmChunk chunk, AsmLine line) {
         this.chunk = chunk;
         this.line = line;
      }
   }

   private Program program;

   public Pass5AsmOptimization(Program program) {
      this.program = program;
   }

   /**
    * Attempt to perform optimization.
    *
    * @return true if an optimization was performed. false if no optimization was possible.
    */
   public abstract boolean optimize();

   public Program getProgram() {
      return program;
   }

   public AsmProgram getAsmProgram() {
      return program.getAsm();
   }

   public CompileLog getLog() {
      return program.getLog();
   }

   public void remove(List<AsmLine> remove) {
      if(remove == null || remove.isEmpty()) {
         return;
      }
      Set<AsmLine> removeSet = (remove instanceof Set) ? (Set<AsmLine>) remove : new HashSet<>(remove);
      Collection<AsmChunk> chunks = getAsmProgram().getChunks();
      for(AsmChunk chunk : chunks) {
         for(Iterator<AsmLine> iterator = chunk.getLines().iterator(); iterator.hasNext(); ) {
            AsmLine line = iterator.next();
            if(removeSet.contains(line)) {
               getLog().append("Removing instruction " + line.getAsm());
               iterator.remove();
            }
         }
      }
   }

   public boolean remove(ListIterator<AsmLine> lineIt) {
      getLog().append("Removing instruction " + lineIt.previous());
      lineIt.remove();
      return true;
   }

   /**
    * Checks if an instruction preserves both Z (Zero) and N (Negative) flags on MOS 6502/6510.
    * Stores, stack pointer store, no-ops and carry/decimal/interrupt/overflow flag modifiers
    * do NOT alter the Z and N flags.
    */
   public static boolean isInstructionPreservingZAndN(AsmInstruction inst) {
      if(inst == null || inst.getCpuOpcode() == null) return false;
      String mnem = inst.getCpuOpcode().getMnemonic();
      // Stores do not modify flags on 6502/6510
      if(mnem.equals("sta") || mnem.equals("stx") || mnem.equals("sty") || mnem.equals("sax")) {
         return true;
      }
      // Flag instructions that modify C, D, I, or V, but leave Z and N completely untouched
      if(mnem.equals("clc") || mnem.equals("sec") || mnem.equals("cld") || mnem.equals("sed") ||
         mnem.equals("cli") || mnem.equals("sei") || mnem.equals("clv")) {
         return true;
      }
      // Stack pointer register store and no-op
      if(mnem.equals("txs") || mnem.equals("nop")) {
         return true;
      }
      return false;
   }

   /**
    * Checks if an instruction establishes the Z and N flags for the given register.
    */
   public static boolean doesInstructionSetZAndN(AsmInstruction inst, String reg) {
      if(inst == null || inst.getCpuOpcode() == null) return false;
      String mnem = inst.getCpuOpcode().getMnemonic();
      CpuAddressingMode mode = inst.getCpuOpcode().getAddressingMode();
      if("A".equals(reg)) {
         return mnem.equals("lda") || mnem.equals("pla") ||
                mnem.equals("tax") || mnem.equals("txa") ||
                mnem.equals("tay") || mnem.equals("tya") ||
                mnem.equals("and") || mnem.equals("ora") || mnem.equals("eor") ||
                mnem.equals("adc") || mnem.equals("sbc") ||
                mnem.equals("lax") || mnem.equals("alr") || mnem.equals("anc") || mnem.equals("arr") ||
                mnem.equals("dcp") || mnem.equals("isc") || mnem.equals("rla") || mnem.equals("rra") ||
                mnem.equals("slo") || mnem.equals("sre") ||
                ((mnem.equals("asl") || mnem.equals("lsr") || mnem.equals("rol") || mnem.equals("ror")) && mode.equals(CpuAddressingMode.NON));
      } else if("X".equals(reg)) {
         return mnem.equals("ldx") || mnem.equals("inx") || mnem.equals("dex") ||
                mnem.equals("tax") || mnem.equals("tsx") || mnem.equals("lax") || mnem.equals("axs");
      } else if("Y".equals(reg)) {
         return mnem.equals("ldy") || mnem.equals("iny") || mnem.equals("dey") || mnem.equals("tay");
      }
      return false;
   }

   /**
    * Scans backwards from inst2Idx to determine if the Z and N flags are guaranteed to match the given register.
    * Skips non-flag-modifying instructions (stores, CLC, SEC, etc.) and analyzes incoming predecessors if a label is hit.
    */
   public static boolean areFlagsEstablishedForReg(List<LineRef> allLines, int inst2Idx, String reg, String currentScope) {
      // 1. Scan backwards within the same basic block, skipping non-flag-modifying stores/instructions
      AsmLabel labelHit = null;
      int labelIdx = -1;
      for(int p = inst2Idx - 1; p >= 0; p--) {
         AsmLine line = allLines.get(p).line;
         if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl) continue;
         if(line instanceof AsmLabel) {
            labelHit = (AsmLabel) line;
            labelIdx = p;
            break;
         }
         if(line instanceof AsmScopeBegin || line instanceof AsmScopeEnd || line instanceof AsmInlineKickAsm) {
            return false;
         }
         if(line instanceof AsmInstruction) {
            AsmInstruction prev = (AsmInstruction) line;
            if(prev.getCpuOpcode() == null) return false;
            if(isInstructionPreservingZAndN(prev)) {
               continue; // Stores and status flags (CLC, SEC, etc.) do not modify Z/N on 6502
            }
            return doesInstructionSetZAndN(prev, reg);
         }
      }

      // 2. If we hit a label at the block entry, check all incoming predecessors to this label
      if(labelHit != null && currentScope != null && !currentScope.isEmpty()) {
         Set<String> targetLabels = new HashSet<>();
         targetLabels.add(labelHit.getLabel());

         // Collect all contiguous labels immediately before or after labelHit
         int firstLabelIdx = labelIdx;
         for(int p = labelIdx - 1; p >= 0; p--) {
            AsmLine line = allLines.get(p).line;
            if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl) continue;
            if(line instanceof AsmLabel) {
               targetLabels.add(((AsmLabel) line).getLabel());
               firstLabelIdx = p;
               continue;
            }
            break;
         }
         for(int p = labelIdx + 1; p < allLines.size(); p++) {
            AsmLine line = allLines.get(p).line;
            if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl) continue;
            if(line instanceof AsmLabel) {
               targetLabels.add(((AsmLabel) line).getLabel());
               continue;
            }
            break;
         }

         List<Integer> predIndices = new ArrayList<>();

         // Check fallthrough immediately before firstLabelIdx
         for(int p = firstLabelIdx - 1; p >= 0; p--) {
            AsmLine line = allLines.get(p).line;
            if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl) continue;
            if(line instanceof AsmInstruction) {
               AsmInstruction prevInst = (AsmInstruction) line;
               if(prevInst.getCpuOpcode() != null) {
                  String pm = prevInst.getCpuOpcode().getMnemonic();
                  if(!pm.equals("jmp") && !pm.equals("rts") && !pm.equals("rti")) {
                     predIndices.add(p);
                  }
               }
            }
            break;
         }

         // Check jumps/branches to targetLabels strictly within currentScope
         String scanScope = null;
         for(int k = 0; k < allLines.size(); k++) {
            LineRef ref = allLines.get(k);
            if(ref.line instanceof AsmScopeBegin) {
               scanScope = ((AsmScopeBegin) ref.line).getLabel();
               continue;
            } else if(ref.line instanceof AsmScopeEnd) {
               scanScope = null;
               continue;
            }
            if(scanScope == null || !scanScope.equals(currentScope)) {
               continue;
            }
            if(ref.line instanceof AsmInstruction) {
               AsmInstruction inst = (AsmInstruction) ref.line;
               if(inst.getCpuOpcode() != null && inst.getCpuOpcode().isJump()) {
                  String dest = inst.getOperandJumpTarget();
                  if(dest != null) {
                     for(String tLab : targetLabels) {
                        if(dest.equals(tLab) || dest.equals("!" + tLab + "+") || dest.equals(tLab + "+")) {
                           predIndices.add(k);
                           break;
                        }
                     }
                  }
               }
            }
         }

         if(predIndices.isEmpty()) return false;

         for(int predIdx : predIndices) {
            boolean predOk = false;
            for(int p = predIdx; p >= 0; p--) {
               AsmLine line = allLines.get(p).line;
               if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl) continue;
               if(line instanceof AsmInstruction) {
                  AsmInstruction pi = (AsmInstruction) line;
                  if(pi.getCpuOpcode() == null) break;
                  String pm = pi.getCpuOpcode().getMnemonic();
                  if(pm.equals("jmp")) {
                     continue; // Look before unconditional jump
                  }
                  if(pm.startsWith("b")) {
                     break; // Branches test different conditions; flags are NOT established!
                  }
                  if(isInstructionPreservingZAndN(pi)) {
                     continue; // Stores do not modify flags
                  }
                  if(doesInstructionSetZAndN(pi, reg)) {
                     predOk = true;
                  }
                  break;
               }
               break;
            }
            if(!predOk) return false;
         }
         return true;
      }

      return false;
   }

   /**
    * Checks if flags (Z, N, C, V) are needed by subsequent branch instructions before being overwritten.
    */
   public static boolean areFlagsNeededAfter(List<LineRef> allLines, int currentIdx) {
      for(int k = currentIdx + 1; k < allLines.size(); k++) {
         AsmLine line = allLines.get(k).line;
         if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl) continue;
         if(line instanceof AsmLabel || line instanceof AsmScopeBegin || line instanceof AsmScopeEnd || line instanceof AsmInlineKickAsm) {
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
            if(isInstructionPreservingZAndN(inst)) {
               continue;
            }
            return false;
         }
      }
      return false;
   }
}
