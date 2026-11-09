package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuClobber;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.values.ScopeRef;

import java.util.*;

/**
 * Multi-Entry Byte-Skipping via BIT $xxxx (.byte $2c).
 *
 * Pattern B (Branch Byte-Skipping):
 * In if-else sequences where both branches load immediate values into the same register
 * before merging:
 *   LDA #val1
 *   JMP target
 *   label:
 *   LDA #val2
 *   target:
 * The JMP target (which jumps over a 2-byte immediate instruction) is replaced by .byte $2c (BIT abs).
 * When falling through, the CPU executes BIT $xxxx, consuming the 2-byte LDA #val2 as an operand
 * and skipping it in 4 cycles without altering register A, X, or Y.
 * Saves 2 bytes (1 byte instead of 3 bytes for JMP) and eliminates jump overhead.
 */
public class Pass5MultiEntryByteSkipping extends Pass5AsmOptimization {

   public static class LineRef {
      final AsmChunk chunk;
      final int indexInChunk;
      final AsmLine line;
      final String scope;

      LineRef(AsmChunk chunk, int indexInChunk, AsmLine line, String scope) {
         this.chunk = chunk;
         this.indexInChunk = indexInChunk;
         this.line = line;
         this.scope = scope;
      }
   }

   private static class ProcedureData {
      final String name;
      final List<AsmChunk> chunks = new ArrayList<>();
      final List<AsmInstruction> instructions = new ArrayList<>();

      ProcedureData(String name) {
         this.name = name;
      }
   }

   public Pass5MultiEntryByteSkipping(Program program) {
      super(program);
   }

   private static Long parseConstValue(String val) {
      if(val == null) return null;
      String t = val.trim();
      if(t.startsWith("#")) t = t.substring(1).trim();
      if(t.startsWith("<") || t.startsWith(">")) return null; // Reject address lo/hi
      if(t.startsWith("'") && t.endsWith("'") && t.length() >= 3) {
         return (long) t.charAt(1);
      }
      try {
         if(t.startsWith("$")) {
            return Long.parseLong(t.substring(1), 16);
         } else if(t.startsWith("0x") || t.startsWith("0X")) {
            return Long.parseLong(t.substring(2), 16);
         } else if(t.startsWith("%")) {
            return Long.parseLong(t.substring(1), 2);
         } else {
            return Long.parseLong(t);
         }
      } catch(NumberFormatException e) {
         return null;
      }
   }

   private static boolean isImmediateLoad(AsmInstruction inst) {
      if(inst == null || inst.getCpuOpcode() == null) return false;
      String mnem = inst.getCpuOpcode().getMnemonic();
      if(!("lda".equals(mnem) || "ldx".equals(mnem) || "ldy".equals(mnem))) return false;
      return CpuAddressingMode.IMM.equals(inst.getCpuOpcode().getAddressingMode());
   }

   private static boolean isConditionalBranch(AsmInstruction inst) {
      if(inst == null || inst.getCpuOpcode() == null) return false;
      String mnem = inst.getCpuOpcode().getMnemonic();
      return mnem.equals("beq") || mnem.equals("bne") || mnem.equals("bpl") || mnem.equals("bmi") ||
             mnem.equals("bvc") || mnem.equals("bvs") || mnem.equals("bcc") || mnem.equals("bcs");
   }

   private static boolean matchesLabel(String labelName, String target, String scope) {
      if(labelName == null || target == null) return false;
      if(labelName.equals(target)) return true;
      if(scope != null) {
         if((scope + "::" + target).equals(labelName)) return true;
         if((scope + "::" + labelName).equals(target)) return true;
      }
      if(target.endsWith("::" + labelName)) return true;
      if(labelName.endsWith("::" + target)) return true;
      return false;
   }

   @Override
   public boolean optimize() {
      boolean overallModified = false;
      boolean passModified = true;

      while(passModified) {
         passModified = false;

         List<LineRef> allLines = new ArrayList<>();
         for(AsmChunk chunk : getAsmProgram().getChunkList()) {
            String scope = chunk.getScopeLabel();
            List<AsmLine> lines = chunk.getLines();
            for(int i = 0; i < lines.size(); i++) {
               allLines.add(new LineRef(chunk, i, lines.get(i), scope));
            }
         }

         // Scan for Pattern B (Branch Byte-Skipping)
         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref1 = allLines.get(i);
            if(!(ref1.line instanceof AsmInstruction)) continue;
            AsmInstruction inst1 = (AsmInstruction) ref1.line;
            if(!isImmediateLoad(inst1) || inst1.isNoOptimize()) continue;
            String reg1 = inst1.getCpuOpcode().getMnemonic();

            // Next instruction must be JMP
            int idxJmp = -1;
            for(int j = i + 1; j < allLines.size(); j++) {
               AsmLine l = allLines.get(j).line;
               if(l instanceof AsmComment || l instanceof AsmConstant || l instanceof AsmLabelDecl) continue;
               if(l instanceof AsmInstruction) {
                  idxJmp = j;
                  break;
               }
               break;
            }
            if(idxJmp == -1) continue;

            LineRef refJmp = allLines.get(idxJmp);
            AsmInstruction instJmp = (AsmInstruction) refJmp.line;
            if(instJmp.getCpuOpcode() == null || instJmp.isNoOptimize()) continue;
            if(!"jmp".equals(instJmp.getCpuOpcode().getMnemonic())) continue;
            if(!CpuAddressingMode.ABS.equals(instJmp.getCpuOpcode().getAddressingMode())) continue;

            String jumpTarget = instJmp.getOperandJumpTarget();
            if(jumpTarget == null) continue;

            // Find next instruction after JMP (skipping comments and labels)
            int idx2 = -1;
            boolean hadScopeBoundary = false;
            for(int j = idxJmp + 1; j < allLines.size(); j++) {
               AsmLine l = allLines.get(j).line;
               if(l instanceof AsmComment || l instanceof AsmConstant || l instanceof AsmLabelDecl || l instanceof AsmLabel) continue;
               if(l instanceof AsmScopeBegin || l instanceof AsmScopeEnd) {
                  hadScopeBoundary = true;
                  break;
               }
               if(l instanceof AsmInstruction) {
                  idx2 = j;
                  break;
               }
               break;
            }
            if(idx2 == -1 || hadScopeBoundary) continue;

            LineRef ref2 = allLines.get(idx2);
            AsmInstruction inst2 = (AsmInstruction) ref2.line;
            if(!isImmediateLoad(inst2) || inst2.isNoOptimize()) continue;
            String reg2 = inst2.getCpuOpcode().getMnemonic();
            if(!reg1.equals(reg2)) continue; // Must load into same register

            int bytesToSkip = inst2.getLineBytes();
            if(bytesToSkip != 2) continue; // .byte $2c skips exactly 2 bytes

            // Right after inst2, the target label must be present
            boolean foundTargetLabel = false;
            int idxAfterTarget = -1;
            for(int j = idx2 + 1; j < allLines.size(); j++) {
               AsmLine l = allLines.get(j).line;
               if(l instanceof AsmComment || l instanceof AsmConstant || l instanceof AsmLabelDecl) continue;
               if(l instanceof AsmLabel) {
                  String lbl = ((AsmLabel) l).getLabel();
                  if(matchesLabel(lbl, jumpTarget, refJmp.scope)) {
                     foundTargetLabel = true;
                     continue;
                  }
               }
               if(l instanceof AsmInstruction) {
                  idxAfterTarget = j;
                  break;
               }
               if(l instanceof AsmScopeBegin || l instanceof AsmScopeEnd) {
                  break;
               }
            }

            if(!foundTargetLabel) continue;

            // Verify that instruction after target does not depend on condition flags
            if(idxAfterTarget != -1) {
               AsmInstruction instAfter = (AsmInstruction) allLines.get(idxAfterTarget).line;
               if(isConditionalBranch(instAfter)) {
                  // Next instruction is a conditional branch that might depend on N/V/Z flags set by BIT
                  continue;
               }
            }

            // All checks passed! Replace JMP with .byte $2c
            getLog().append("Optimizing JMP " + jumpTarget + " to .byte $2c (BIT $xxxx byte-skipper over " + inst2.getAsm() + ")");

            AsmInlineKickAsm byteSkipper = new AsmInlineKickAsm(".byte $2c\n", 1L, 4L, new CpuClobber("nzv"));
            int chunkIdx = refJmp.chunk.getLines().indexOf(instJmp);
            if(chunkIdx != -1) {
               refJmp.chunk.getLines().set(chunkIdx, byteSkipper);
               passModified = true;
               overallModified = true;
               break; // Re-scan
            }
         }

         if(passModified) continue;

         // Scan for Pattern A (Procedure-Level Entry-Point Merging)
         List<ProcedureData> procedures = new ArrayList<>();
         ProcedureData currentProc = null;
         for(AsmChunk chunk : getAsmProgram().getChunkList()) {
            String scope = chunk.getScopeLabel();
            if(scope != null && !scope.equals(ScopeRef.ROOT.getFullName())) {
               if(currentProc == null || !currentProc.name.equals(scope)) {
                  currentProc = new ProcedureData(scope);
                  procedures.add(currentProc);
               }
               currentProc.chunks.add(chunk);
               for(AsmLine l : chunk.getLines()) {
                  if(l instanceof AsmInstruction) {
                     currentProc.instructions.add((AsmInstruction) l);
                  }
               }
            } else {
               currentProc = null;
            }
         }

         for(int p = 0; p < procedures.size() - 1; p++) {
            ProcedureData p1 = procedures.get(p);
            ProcedureData p2 = procedures.get(p + 1);

            List<AsmInstruction> insts1 = p1.instructions;
            List<AsmInstruction> insts2 = p2.instructions;

            if(insts1.size() < 2 || insts2.size() != insts1.size()) continue;

            // p1 must not have data segments, segment changes or non-instruction content (strings, tables)
            boolean hasSegmentOrData = false;
            for(AsmChunk ch : p1.chunks) {
               for(AsmLine l : ch.getLines()) {
                  if(l instanceof AsmSegment) {
                     hasSegmentOrData = true;
                     break;
                  }
                  if(l instanceof AsmInlineKickAsm inlineAsm && (inlineAsm.getAsm().contains(".text") || inlineAsm.getAsm().contains(".byte") || inlineAsm.getAsm().contains(".segment"))) {
                     hasSegmentOrData = true;
                     break;
                  }
                  if(l instanceof AsmConstant) {
                     hasSegmentOrData = true;
                     break;
                  }
               }
               if(hasSegmentOrData) break;
            }
            if(hasSegmentOrData) continue;

            AsmInstruction first1 = insts1.get(0);
            AsmInstruction first2 = insts2.get(0);

            if(!isImmediateLoad(first1) || !isImmediateLoad(first2)) continue;
            if(!first1.getCpuOpcode().getMnemonic().equals(first2.getCpuOpcode().getMnemonic())) continue;
            if(first1.getLineBytes() != 2 || first2.getLineBytes() != 2) continue;

            // Operands must be pure scalar/numeric constants, NOT label references like #<msg or #>msg
            Long const1 = parseConstValue(first1.getOperand1());
            Long const2 = parseConstValue(first2.getOperand1());
            if(const1 == null || const2 == null) continue;

            // Last instruction must be RTS
            AsmInstruction last1 = insts1.get(insts1.size() - 1);
            AsmInstruction last2 = insts2.get(insts2.size() - 1);
            if(last1.getCpuOpcode() == null || last2.getCpuOpcode() == null) continue;
            if(!"rts".equals(last1.getCpuOpcode().getMnemonic()) || !"rts".equals(last2.getCpuOpcode().getMnemonic())) continue;

            // Check if remaining instructions (index 1 to end) are identical
            boolean identical = true;
            for(int k = 1; k < insts1.size(); k++) {
               AsmInstruction i1 = insts1.get(k);
               AsmInstruction i2 = insts2.get(k);
               if(!Objects.equals(i1.getCpuOpcode(), i2.getCpuOpcode())) {
                  identical = false;
                  break;
               }
               if(!Objects.equals(i1.getOperand1(), i2.getOperand1())) {
                  identical = false;
                  break;
               }
               if(!Objects.equals(i1.getOperand2(), i2.getOperand2())) {
                  identical = false;
                  break;
               }
               // Instructions must not reference local symbols of p1 or p2
               if(i1.getOperand1() != null && (i1.getOperand1().contains(p1.name + ".") || i1.getOperand1().contains(p2.name + "."))) {
                  identical = false;
                  break;
               }
            }
            if(!identical) continue;

            // Merge! In p1: keep first1, append .byte $2c, remove all subsequent instructions
            getLog().append("Merging procedure entries: " + p1.name + " -> " + p2.name + " via .byte $2c");

            // Find chunk containing first1
            AsmChunk chunkFirst = null;
            int idxFirst = -1;
            for(AsmChunk ch : p1.chunks) {
               idxFirst = ch.getLines().indexOf(first1);
               if(idxFirst != -1) {
                  chunkFirst = ch;
                  break;
               }
            }
            if(chunkFirst == null) continue;

            // In chunkFirst: retain lines up to first1, add .byte $2c, remove remaining lines (except ScopeEnd)
            List<AsmLine> newLines = new ArrayList<>();
            for(int k = 0; k <= idxFirst; k++) {
               newLines.add(chunkFirst.getLines().get(k));
            }
            newLines.add(new AsmInlineKickAsm(".byte $2c\n", 1L, 4L, new CpuClobber("nzv")));
            for(int k = idxFirst + 1; k < chunkFirst.getLines().size(); k++) {
               AsmLine l = chunkFirst.getLines().get(k);
               if(l instanceof AsmScopeEnd) {
                  newLines.add(l);
               }
            }
            chunkFirst.getLines().clear();
            chunkFirst.getLines().addAll(newLines);

            // In subsequent chunks of p1: remove all instructions, retain AsmScopeEnd
            boolean foundFirstChunk = false;
            for(AsmChunk ch : p1.chunks) {
               if(ch == chunkFirst) {
                  foundFirstChunk = true;
                  continue;
               }
               if(foundFirstChunk) {
                  List<AsmLine> chLines = new ArrayList<>();
                  for(AsmLine l : ch.getLines()) {
                     if(l instanceof AsmScopeEnd) {
                        chLines.add(l);
                     }
                  }
                  ch.getLines().clear();
                  ch.getLines().addAll(chLines);
               }
            }

            passModified = true;
            overallModified = true;
            break;
         }
      }

      return overallModified;
   }
}
