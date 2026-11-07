package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;

import java.util.*;

/**
 * Identify and merge identical duplicate basic blocks within procedure scopes.
 * Handles both directly adjacent twin blocks (zero-rewrite path) and non-adjacent blocks.
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

   private static boolean isDirectlyAdjacent(BasicBlock b1, BasicBlock b2, List<LineRef> sLines) {
      int idx1 = sLines.indexOf(b1.instructionRefs.get(b1.instructionRefs.size() - 1));
      int idx2 = sLines.indexOf(b2.labelRef);
      if(idx1 == -1 || idx2 == -1 || idx2 <= idx1) return false;
      for(int k = idx1 + 1; k < idx2; k++) {
         AsmLine l = sLines.get(k).line;
         if(l instanceof AsmComment || l instanceof AsmConstant || l instanceof AsmLabelDecl) continue;
         return false;
      }
      return true;
   }

   @Override
   public boolean optimize() {
      boolean overallModified = false;
      boolean passModified = true;

      while(passModified) {
         passModified = false;

         List<LineRef> allLines = new ArrayList<>();
         for(AsmChunk chunk : getAsmProgram().getChunks()) {
            for(AsmLine line : chunk.getLines()) {
               allLines.add(new LineRef(chunk, line));
            }
         }

         // Group lines strictly by local scope
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
                  // Only consider compiler-generated local block labels (__b1, __b2, etc.)
                  if(labelName == null || !labelName.startsWith("__b")) continue;

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
                        if(inst.getOperand1() != null && inst.getOperand1().startsWith("!")) {
                           validBlock = false;
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
            boolean mergedPair = false;

            // Fast Path: Check directly adjacent duplicate pairs first
            for(int i = 0; i < blocks.size() - 1; i++) {
               BasicBlock b1 = blocks.get(i);
               BasicBlock b2 = blocks.get(i + 1);
               if(isDirectlyAdjacent(b1, b2, sLines) && areInstructionsIdentical(b1.instructionRefs, b2.instructionRefs)) {
                  getLog().append("Adjacent tail-merge in " + entry.getKey() + ": collapsing " + b1.labelName + " body into " + b2.labelName);
                  // Delete b1 instructions, leave b1 label stacked directly on b2 label.
                  // No jump rewriting needed! Fallthrough into b1 falls into b2, jumps to b1 jump to b2.
                  for(LineRef instRef : b1.instructionRefs) {
                     instRef.chunk.getLines().remove(instRef.line);
                  }
                  passModified = true;
                  overallModified = true;
                  mergedPair = true;
                  break;
               }
            }

            if(mergedPair) break;

            // General Path: Non-adjacent duplicate blocks within this scope
            BasicBlock canonical = null;
            BasicBlock duplicate = null;

            for(int i = 0; i < blocks.size(); i++) {
               BasicBlock b1 = blocks.get(i);
               for(int j = i + 1; j < blocks.size(); j++) {
                  BasicBlock b2 = blocks.get(j);
                  if(!areInstructionsIdentical(b1.instructionRefs, b2.instructionRefs)) continue;

                  BasicBlock candCanonical = null;
                  BasicBlock candDuplicate = null;

                  if(!b2.hasFallthroughEntry) {
                     candCanonical = b1;
                     candDuplicate = b2;
                  } else if(!b1.hasFallthroughEntry) {
                     candCanonical = b2;
                     candDuplicate = b1;
                  }

                  if(candCanonical != null && candDuplicate != null) {
                     // Check safe branch distances for any conditional branch targeting candDuplicate
                     int targetIdx = sLines.indexOf(candCanonical.labelRef);
                     boolean safeDistance = true;
                     for(int k = 0; k < sLines.size(); k++) {
                        LineRef ref = sLines.get(k);
                        if(ref.line instanceof AsmInstruction) {
                           AsmInstruction inst = (AsmInstruction) ref.line;
                           if(inst.getOperand1() != null && inst.getOperand1().trim().equals(candDuplicate.labelName.trim())) {
                              String mnem = inst.getCpuOpcode() != null ? inst.getCpuOpcode().getMnemonic() : "";
                              if(mnem.startsWith("b")) {
                                 if(Math.abs(k - targetIdx) > 35) {
                                    safeDistance = false;
                                    break;
                                 }
                              }
                           }
                        }
                     }
                     if(safeDistance) {
                        canonical = candCanonical;
                        duplicate = candDuplicate;
                        break;
                     }
                  }
               }
               if(duplicate != null) break;
            }

            if(canonical != null && duplicate != null) {
               String keepLabel = canonical.labelName.trim();
               String removeLabel = duplicate.labelName.trim();

               getLog().append("Merging non-adjacent duplicate block in " + entry.getKey() + ": " + removeLabel + " into " + keepLabel);

               // Rewrite jumps/branches strictly within this local procedure scope (sLines)
               for(LineRef ref : sLines) {
                  if(ref.line instanceof AsmInstruction) {
                     AsmInstruction inst = (AsmInstruction) ref.line;
                     if(inst.getOperand1() != null && inst.getOperand1().trim().equals(removeLabel)) {
                        inst.setOperand1(keepLabel);
                     }
                  }
               }

               // Remove duplicate block lines
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
