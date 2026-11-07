package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;

import java.util.*;

/**
 * Assembler Peephole Optimization: Duplicate Basic Block Elimination (Tail Merging).
 * Identifies identical basic blocks within a procedure scope that end with the same unconditional
 * transfer (jmp <target>, rts, or rti) and where the duplicate block has no fallthrough entry.
 * Rewrites all branch and jump targets pointing to the duplicate block to point to the canonical
 * block instead, and deletes the duplicate block.
 */
public class Pass5DuplicateBlockElimination extends Pass5AsmOptimization {

   private static class LineRef {
      final AsmChunk chunk;
      final AsmLine line;

      LineRef(AsmChunk chunk, AsmLine line) {
         this.chunk = chunk;
         this.line = line;
      }
   }

   private static class BasicBlock {
      final String labelName;
      final LineRef labelRef;
      final boolean hasFallthroughEntry;
      final List<LineRef> instructionRefs;

      BasicBlock(String labelName, LineRef labelRef, boolean hasFallthroughEntry, List<LineRef> instructionRefs) {
         this.labelName = labelName;
         this.labelRef = labelRef;
         this.hasFallthroughEntry = hasFallthroughEntry;
         this.instructionRefs = instructionRefs;
      }
   }

   public Pass5DuplicateBlockElimination(Program program) {
      super(program);
   }

   private static boolean isUnconditionalTransfer(AsmInstruction inst) {
      if(inst == null || inst.getCpuOpcode() == null) return false;
      String mnem = inst.getCpuOpcode().getMnemonic();
      return mnem.equals("jmp") || mnem.equals("rts") || mnem.equals("rti");
   }

   private static boolean areInstructionsIdentical(List<LineRef> list1, List<LineRef> list2) {
      if(list1.size() != list2.size()) return false;
      for(int i = 0; i < list1.size(); i++) {
         AsmInstruction inst1 = (AsmInstruction) list1.get(i).line;
         AsmInstruction inst2 = (AsmInstruction) list2.get(i).line;
         if(inst1.getCpuOpcode() == null || inst2.getCpuOpcode() == null) return false;
         if(!inst1.getCpuOpcode().equals(inst2.getCpuOpcode())) return false;
         String op1 = inst1.getOperand1();
         String op2 = inst2.getOperand1();
         if(op1 == null && op2 != null) return false;
         if(op1 != null && !op1.equals(op2)) return false;
      }
      return true;
   }

   @Override
   public boolean optimize() {
      boolean overallModified = false;
      boolean passModified = true;

      while(passModified) {
         passModified = false;

         // Build a flat sequential list of all lines across all chunks
         List<LineRef> allLines = new ArrayList<>();
         for(AsmChunk chunk : getAsmProgram().getChunks()) {
            for(AsmLine line : chunk.getLines()) {
               allLines.add(new LineRef(chunk, line));
            }
         }

         // Group lines by scope
         Map<String, List<LineRef>> scopeLinesMap = new LinkedHashMap<>();
         String currentScope = "";
         for(LineRef ref : allLines) {
            if(ref.line instanceof AsmScopeBegin) {
               currentScope = ((AsmScopeBegin) ref.line).getLabel();
            } else if(ref.line instanceof AsmScopeEnd) {
               currentScope = "";
            } else if(currentScope != null && !currentScope.isEmpty()) {
               scopeLinesMap.computeIfAbsent(currentScope, k -> new ArrayList<>()).add(ref);
            }
         }

         for(Map.Entry<String, List<LineRef>> entry : scopeLinesMap.entrySet()) {
            List<LineRef> sLines = entry.getValue();
            List<BasicBlock> blocks = new ArrayList<>();

            // Find all candidate basic blocks ending in jmp / rts / rti within this scope
            for(int i = 0; i < sLines.size(); i++) {
               LineRef lRef = sLines.get(i);
               if(lRef.line instanceof AsmLabel) {
                  AsmLabel label = (AsmLabel) lRef.line;
                  String labelName = label.getLabel();

                  // Check if preceded by unconditional transfer
                  boolean hasFallthrough = true;
                  for(int p = i - 1; p >= 0; p--) {
                     AsmLine pLine = sLines.get(p).line;
                     if(pLine instanceof AsmComment || pLine instanceof AsmConstant || pLine instanceof AsmLabelDecl) continue;
                     if(pLine instanceof AsmInstruction) {
                        if(isUnconditionalTransfer((AsmInstruction) pLine)) {
                           hasFallthrough = false;
                        }
                     }
                     break;
                  }

                  // Collect instructions in this block until unconditional transfer or label
                  List<LineRef> blockInsts = new ArrayList<>();
                  boolean validBlock = false;

                  for(int j = i + 1; j < sLines.size(); j++) {
                     LineRef jRef = sLines.get(j);
                     if(jRef.line instanceof AsmComment || jRef.line instanceof AsmConstant || jRef.line instanceof AsmLabelDecl) continue;
                     if(jRef.line instanceof AsmLabel || jRef.line instanceof AsmScopeBegin || jRef.line instanceof AsmScopeEnd || jRef.line instanceof AsmInlineKickAsm) {
                        break;
                     }
                     if(jRef.line instanceof AsmInstruction) {
                        AsmInstruction inst = (AsmInstruction) jRef.line;
                        if(inst.isNoOptimize() || inst.getCpuOpcode() == null) {
                           break;
                        }
                        blockInsts.add(jRef);
                        if(isUnconditionalTransfer(inst)) {
                           validBlock = true;
                           break;
                        }
                     }
                  }

                  if(validBlock && !blockInsts.isEmpty()) {
                     // Ensure block instructions do not reference the block's own label (no self-loops)
                     boolean hasSelfRef = false;
                     for(LineRef instRef : blockInsts) {
                        AsmInstruction inst = (AsmInstruction) instRef.line;
                        if(inst.getOperand1() != null && inst.getOperand1().trim().equals(labelName.trim())) {
                           hasSelfRef = true;
                           break;
                        }
                     }
                     if(!hasSelfRef) {
                        blocks.add(new BasicBlock(labelName, lRef, hasFallthrough, blockInsts));
                     }
                  }
               }
            }

            if(blocks.size() < 2) continue;

            // Search for duplicate block pairs
            BasicBlock canonical = null;
            BasicBlock duplicate = null;

            for(int i = 0; i < blocks.size(); i++) {
               BasicBlock b1 = blocks.get(i);
               for(int j = i + 1; j < blocks.size(); j++) {
                  BasicBlock b2 = blocks.get(j);

                  if(!b2.hasFallthroughEntry && areInstructionsIdentical(b1.instructionRefs, b2.instructionRefs)) {
                     canonical = b1;
                     duplicate = b2;
                     break;
                  } else if(!b1.hasFallthroughEntry && areInstructionsIdentical(b2.instructionRefs, b1.instructionRefs)) {
                     canonical = b2;
                     duplicate = b1;
                     break;
                  }
               }
               if(duplicate != null) break;
            }

            if(canonical != null && duplicate != null) {
               String keepLabel = canonical.labelName.trim();
               String removeLabel = duplicate.labelName.trim();

               getLog().append("Merging duplicate basic block " + removeLabel + " into " + keepLabel + " (" + duplicate.instructionRefs.size() + " instructions)");

               // Rewrite all jumps/branches pointing to removeLabel across all chunks
               for(LineRef ref : allLines) {
                  if(ref.line instanceof AsmInstruction) {
                     AsmInstruction inst = (AsmInstruction) ref.line;
                     if(inst.getOperand1() != null && inst.getOperand1().trim().equals(removeLabel)) {
                        inst.setOperand1(keepLabel);
                     }
                  }
               }

               // Remove duplicate basic block lines from their respective chunks
               duplicate.labelRef.chunk.getLines().remove(duplicate.labelRef.line);
               for(LineRef instRef : duplicate.instructionRefs) {
                  instRef.chunk.getLines().remove(instRef.line);
               }

               passModified = true;
               overallModified = true;
               break;
            }
         }
      }

      return overallModified;
   }
}
