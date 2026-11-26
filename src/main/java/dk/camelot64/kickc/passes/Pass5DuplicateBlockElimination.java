package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;

import java.util.*;

/**
 * Identify and merge identical duplicate basic blocks within procedure scopes.
 * Handles both directly adjacent twin blocks (zero-rewrite path) and non-adjacent blocks.
 */
public class Pass5DuplicateBlockElimination extends Pass5AsmOptimization {

   private static final class BlockSignature {
      private final List<LineRef> insts;
      private final int hash;

      BlockSignature(List<LineRef> insts) {
         this.insts = insts;
         int h = insts.size();
         for(int i = 0; i < insts.size(); i++) {
            AsmInstruction inst = (AsmInstruction) insts.get(i).line;
            CpuOpcode op = inst.getCpuOpcode();
            String op1 = inst.getOperand1();
            h = 31 * h + (op != null ? op.hashCode() : 0);
            h = 31 * h + (op1 != null ? op1.hashCode() : 0);
         }
         this.hash = h;
      }

      @Override
      public int hashCode() {
         return hash;
      }

      @Override
      public boolean equals(Object o) {
         if(this == o) return true;
         if(!(o instanceof BlockSignature other)) return false;
         if(this.hash != other.hash) return false;
         return areInstructionsIdentical(this.insts, other.insts);
      }
   }

   private static class BasicBlock {
      final String labelName;
      final LineRef labelRef;
      final int labelIdx;
      final int lastInstIdx;
      final boolean hasFallthroughEntry;
      final List<LineRef> instructionRefs;
      final BlockSignature signature;

      BasicBlock(String labelName, LineRef labelRef, int labelIdx, int lastInstIdx,
                 boolean hasFallthroughEntry, List<LineRef> instructionRefs) {
         this.labelName = labelName;
         this.labelRef = labelRef;
         this.labelIdx = labelIdx;
         this.lastInstIdx = lastInstIdx;
         this.hasFallthroughEntry = hasFallthroughEntry;
         this.instructionRefs = instructionRefs;
         this.signature = new BlockSignature(instructionRefs);
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
      int idx1 = b1.lastInstIdx;
      int idx2 = b2.labelIdx;
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

         List<LineRef> allLines = getSharedAllLines();
         Map<String, List<LineRef>> scopeLinesMap = getSharedScopeLinesMap(allLines);

         for(Map.Entry<String, List<LineRef>> entry : scopeLinesMap.entrySet()) {
            String scope = entry.getKey();
            if(scope == null || scope.isEmpty() || !isScopeDirty(scope)) continue;
            List<LineRef> sLines = entry.getValue();
            List<BasicBlock> blocks = new ArrayList<>();

            // Find all candidate basic blocks ending in jmp / rts / rti within this scope
            for(int i = 0; i < sLines.size(); i++) {
               LineRef lRef = sLines.get(i);
               if(lRef.line instanceof AsmLabel) {
                  AsmLabel label = (AsmLabel) lRef.line;
                  if(label.isNoOptimize()) continue;
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
                  int lastInstIdx = -1;

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
                           lastInstIdx = j;
                           break;
                        }
                     } else {
                        // CF-13: Data, segment, alignment, or unknown directives inside a block invalidate deduplication
                        validBlock = false;
                        break;
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
                        blocks.add(new BasicBlock(labelName, lRef, i, lastInstIdx, hasFallthrough, blockInsts));
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
               if(isDirectlyAdjacent(b1, b2, sLines) && b1.signature.equals(b2.signature)) {
                  getLog().append("Adjacent tail-merge in " + entry.getKey() + ": collapsing " + b1.labelName + " body into " + b2.labelName);
                  // Delete b1 instructions, leave b1 label stacked directly on b2 label.
                  // No jump rewriting needed! Fallthrough into b1 falls into b2, jumps to b1 jump to b2.
                  for(LineRef instRef : b1.instructionRefs) {
                     instRef.chunk.getLines().remove(instRef.line);
                  }
                  markScopeDirty(scope);
                  invalidateRoundStructure();
                  passModified = true;
                  overallModified = true;
                  mergedPair = true;
                  break;
               }
            }

            if(mergedPair) break;

            // General Path: Bucket non-adjacent duplicate blocks by structural signature for O(B_p) lookup
            Map<BlockSignature, List<Integer>> buckets = new HashMap<>(blocks.size() * 2);
            boolean hasDuplicateBucket = false;
            for(int i = 0; i < blocks.size(); i++) {
               List<Integer> group = buckets.computeIfAbsent(blocks.get(i).signature, k -> new ArrayList<>(1));
               group.add(i);
               if(group.size() == 2) {
                  hasDuplicateBucket = true;
               }
            }

            BasicBlock canonical = null;
            BasicBlock duplicate = null;

            if(hasDuplicateBucket) {
               Map<String, List<Integer>> branchPositionsByTarget = new HashMap<>();
               for(int k = 0; k < sLines.size(); k++) {
                  LineRef ref = sLines.get(k);
                  if(ref.line instanceof AsmInstruction inst) {
                     String op1 = inst.getOperand1();
                     if(op1 != null) {
                        String mnem = inst.getCpuOpcode() != null ? inst.getCpuOpcode().getMnemonic() : "";
                        if(mnem.startsWith("b")) {
                           branchPositionsByTarget.computeIfAbsent(op1.trim(), x -> new ArrayList<>()).add(k);
                        }
                     }
                  }
               }

               for(int i = 0; i < blocks.size(); i++) {
                  BasicBlock b1 = blocks.get(i);
                  List<Integer> group = buckets.get(b1.signature);
                  if(group.size() < 2) continue;

                  for(int gIdx = 0; gIdx < group.size(); gIdx++) {
                     int j = group.get(gIdx);
                     if(j <= i) continue;
                     BasicBlock b2 = blocks.get(j);

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
                        int targetIdx = candCanonical.labelIdx;
                        boolean safeDistance = true;
                        List<Integer> branchPositions = branchPositionsByTarget.get(candDuplicate.labelName.trim());
                        if(branchPositions != null) {
                           for(int k : branchPositions) {
                              if(Math.abs(k - targetIdx) > 35) {
                                 safeDistance = false;
                                 break;
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

               markScopeDirty(scope);
               invalidateRoundStructure();
               passModified = true;
               overallModified = true;
               break;
            }
         }
      }

      return overallModified;
   }
}
