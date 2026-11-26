package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.Program;
import kickass.KickAssembler65CE02;
import kickass.nonasm.c64.CharToPetsciiConverter;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identify and fix long branches (over 128 bytes).
 * Requires KickAssembler in CLASS path
 */
public class Pass5FixLongBranches extends Pass5AsmOptimization {

   private int retLabelCounter = 0;

   private static class RtsRef {
      final LineRef ref;
      final int offset;
      final String segment;
      final int lineIdx;
      String label;
      final boolean isStandalone;
      boolean isTargeted;
      boolean isRemoved;

      RtsRef(LineRef ref, int offset, String segment, int lineIdx, String label, boolean isStandalone) {
         this.ref = ref;
         this.offset = offset;
         this.segment = segment;
         this.lineIdx = lineIdx;
         this.label = label;
         this.isStandalone = isStandalone;
         this.isTargeted = false;
         this.isRemoved = false;
      }
   }

   private static class BranchToFix {
      final AsmChunk chunk;
      final AsmInstruction instr;

      BranchToFix(AsmChunk chunk, AsmInstruction instr) {
         this.chunk = chunk;
         this.instr = instr;
      }
   }

   public Pass5FixLongBranches(Program program) {
      super(program);
   }

   public boolean optimize() {

      // Make sure KickAssembler is present
      try {
         Class.forName("kickass.KickAssembler", false, getClass().getClassLoader());
      } catch(ClassNotFoundException e) {
         getLog().append("Warning! KickAssembler not found in java CLASSPATH. Cannot eliminate long branches.");
      }

      // Pre-pass: analytically find and fix long branches in memory
      long tPreStart = System.currentTimeMillis();
      int preFixedCount = preFixLongBranches();
      long tPreElapsed = System.currentTimeMillis() - tPreStart;
      if(preFixedCount > 0 && getLog().isVerboseFixLongBranch()) {
         System.out.println(String.format("      [PASS5 PRE-FIX LONG BRANCHES] fixed %d branches in %4.2f s", preFixedCount, tPreElapsed / 1000.0));
      }

      // Relaxation pass: relax short branches back to 2-byte branches and eliminate 0-distance trampolines
      int relaxedCount = relaxShortBranches();
      if(relaxedCount > 0 && getLog().isVerboseFixLongBranch()) {
         System.out.println(String.format("      [PASS5 RELAX SHORT BRANCHES] relaxed %d trampolines", relaxedCount));
      }

      // Perform repeated optimization until no more branches are too long
      boolean anyOptimized = (preFixedCount > 0) || (relaxedCount > 0);
      if(!isAllBranchesAnalyticallySafe()) {
         boolean stepOptimized = true;
         int iter = 0;
         while(stepOptimized) {
            iter++;
            long tStepStart = System.currentTimeMillis();
            stepOptimized = step();
            if(stepOptimized) {
               new Pass5DoubleJumpElimination(getProgram()).optimize();
            }
            long tStepElapsed = System.currentTimeMillis() - tStepStart;
            if(getLog().isVerboseFixLongBranch()) {
               System.out.println(String.format("      [PASS5 LONG BRANCH iter %d] took %6.2f s (fixed=%b)", iter, tStepElapsed / 1000.0, stepOptimized));
            }
            if(stepOptimized) anyOptimized = true;
         }

         // Second relaxation pass after long branch resolution and code shrinking
         int postRelaxedCount = relaxShortBranches();
         if(postRelaxedCount > 0) {
            if(getLog().isVerboseFixLongBranch()) {
               System.out.println(String.format("      [PASS5 POST-RELAX SHORT BRANCHES] relaxed %d trampolines", postRelaxedCount));
            }
            anyOptimized = true;
            if(!isAllBranchesAnalyticallySafe()) {
               // CF-01: Always verify iteratively with KickAssembler that no branch was pushed out of range
               while(step()) {
                  new Pass5DoubleJumpElimination(getProgram()).optimize();
               }
            } else {
               new Pass5ReindexAsmLines(getProgram()).optimize();
            }
         }
      } else if(anyOptimized) {
         new Pass5ReindexAsmLines(getProgram()).optimize();
      }

      return anyOptimized;
   }

   /**
    * Conservatively verifies in memory that every conditional branch in the ASM program
    * resolves within [-126, +125] bytes in the same scope/segment and does not span across
    * directives whose assembled byte size cannot be guaranteed analytically.
    */
   private boolean isAllBranchesAnalyticallySafe() {
      List<LineRef> allLines = flattenLines(getAsmProgram());
      int n = allLines.size();
      int[] lineOffsets = new int[n];
      String[] lineSegments = new String[n];
      int[] unsafeCountPrefix = new int[n + 1];

      Map<String, Integer> labelOffsets = new HashMap<>();
      Map<String, String> labelSegments = new HashMap<>();
      Map<String, Integer> labelLineIndices = new HashMap<>();

      String currentScope = null;
      String currentSegment = "Code";
      Map<String, Integer> segmentOffsets = new HashMap<>();

      for(int i = 0; i < n; i++) {
         AsmLine line = allLines.get(i).line;
         boolean isUnsafeDirective = (line instanceof AsmInlineKickAsm)
               || (line instanceof AsmDataAlignment)
               || (line instanceof AsmLoopAlign)
               || (line instanceof AsmDataKickAsm)
               || (line instanceof AsmSetPc);
         unsafeCountPrefix[i + 1] = unsafeCountPrefix[i] + (isUnsafeDirective ? 1 : 0);

         if(line instanceof AsmScopeBegin) {
            currentScope = ((AsmScopeBegin) line).getLabel();
            currentSegment = "Code";
            segmentOffsets.clear();
         } else if(line instanceof AsmScopeEnd) {
            currentScope = null;
            currentSegment = "Code";
            segmentOffsets.clear();
         } else if(currentScope != null) {
            if(line instanceof AsmSegment) {
               String seg = ((AsmSegment) line).getName();
               currentSegment = (seg != null) ? seg : "Code";
            } else {
               int offset = segmentOffsets.getOrDefault(currentSegment, 0);
               lineOffsets[i] = offset;
               lineSegments[i] = currentSegment;
               if(line instanceof AsmLabel) {
                  String label = ((AsmLabel) line).getLabel();
                  if(label != null && !label.startsWith("!")) {
                     String key = currentScope + "::" + label;
                     if(labelLineIndices.containsKey(key)) {
                        return false;
                     }
                     labelOffsets.put(key, offset);
                     labelSegments.put(key, currentSegment);
                     labelLineIndices.put(key, i);
                  }
               }
               segmentOffsets.put(currentSegment, offset + line.getLineBytes());
            }
         }
      }

      for(int i = 0; i < n; i++) {
         LineRef ref = allLines.get(i);
         if(!(ref.line instanceof AsmInstruction instr)) continue;
         if(instr.getCpuOpcode() == null || invertBranch(instr.getCpuOpcode()) == null) continue;

         String scope = ref.scope;
         if(scope == null || scope.isEmpty()) {
            return false;
         }
         String target = instr.getOperandJumpTarget();
         if(target == null) {
            return false;
         }

         int targetIdx;
         int targetOffset;
         String targetSegment;

         if(target.startsWith("!")) {
            targetIdx = findLabelIndex(allLines, target, i, scope);
            if(targetIdx == -1 || !scope.equals(allLines.get(targetIdx).scope)) {
               return false;
            }
            targetOffset = lineOffsets[targetIdx];
            targetSegment = lineSegments[targetIdx];
         } else {
            String key = scope + "::" + target;
            Integer idxObj = labelLineIndices.get(key);
            if(idxObj == null) {
               return false;
            }
            targetIdx = idxObj;
            targetOffset = labelOffsets.get(key);
            targetSegment = labelSegments.get(key);
         }

         if(!Objects.equals(lineSegments[i], targetSegment)) {
            return false;
         }
         int lo = Math.min(i, targetIdx);
         int hi = Math.max(i, targetIdx);
         if(unsafeCountPrefix[hi] - unsafeCountPrefix[lo + 1] > 0) {
            return false;
         }

         int branchPc = lineOffsets[i] + 2;
         int distance = targetOffset - branchPc;
         if(distance < -126 || distance > 125) {
            return false;
         }
      }

      return true;
   }

   /**
    * Pre-pass to analytically detect and fix long branches across all scopes.
    *
    * @return total number of branches fixed
    */
   private int preFixLongBranches() {
      int totalFixed = 0;
      boolean anyFixedThisPass = true;
      int pass = 0;

      while(anyFixedThisPass) {
         anyFixedThisPass = false;
         pass++;

         // Phase 1: Collect label offsets per scope
         Map<String, Integer> labelOffsets = new HashMap<>();
         String currentScope = null;
         boolean inCode = true;
         int currentOffset = 0;

         for(AsmChunk chunk : getAsmProgram().getChunks()) {
            for(AsmLine line : chunk.getLines()) {
               if(line instanceof AsmScopeBegin) {
                  currentScope = ((AsmScopeBegin) line).getLabel();
                  inCode = true;
                  currentOffset = 0;
               } else if(line instanceof AsmScopeEnd) {
                  currentScope = null;
                  inCode = true;
                  currentOffset = 0;
               } else if(currentScope != null) {
                  if(line instanceof AsmSegment) {
                     String seg = ((AsmSegment) line).getName();
                     if(seg != null && seg.toLowerCase().contains("data")) {
                        inCode = false;
                     } else {
                        inCode = true;
                     }
                  } else if(inCode) {
                     if(line instanceof AsmLabel) {
                        String label = ((AsmLabel) line).getLabel();
                        if(!label.startsWith("!")) {
                           labelOffsets.put(currentScope + "::" + label, currentOffset);
                        }
                     }
                     currentOffset += line.getLineBytes();
                  }
               }
            }
         }

         // Phase 2: Check conditional branches and record long ones
         currentScope = null;
         inCode = true;
         currentOffset = 0;
         List<BranchToFix> branchesToFix = new ArrayList<>();

         for(AsmChunk chunk : getAsmProgram().getChunks()) {
            for(AsmLine line : chunk.getLines()) {
               if(line instanceof AsmScopeBegin) {
                  currentScope = ((AsmScopeBegin) line).getLabel();
                  inCode = true;
                  currentOffset = 0;
               } else if(line instanceof AsmScopeEnd) {
                  currentScope = null;
                  inCode = true;
                  currentOffset = 0;
               } else if(currentScope != null) {
                  if(line instanceof AsmSegment) {
                     String seg = ((AsmSegment) line).getName();
                     if(seg != null && seg.toLowerCase().contains("data")) {
                        inCode = false;
                     } else {
                        inCode = true;
                     }
                  } else if(inCode) {
                     if(line instanceof AsmInstruction) {
                        AsmInstruction instr = (AsmInstruction) line;
                        CpuOpcode inverse = invertBranch(instr.getCpuOpcode());
                        if(inverse != null) {
                           String target = instr.getOperandJumpTarget();
                           if(target != null && !target.startsWith("!")) {
                              Integer targetOffset = labelOffsets.get(currentScope + "::" + target);
                              if(targetOffset != null) {
                                 int branchPc = currentOffset + 2;
                                 int distance = targetOffset - branchPc;
                                 if(distance < -126 || distance > 125) {
                                    branchesToFix.add(new BranchToFix(chunk, instr));
                                 }
                              }
                           }
                        }
                     }
                     currentOffset += line.getLineBytes();
                  }
               }
            }
         }

         for(BranchToFix b : branchesToFix) {
            if(fixLongBranch(b.chunk, b.instr)) {
               anyFixedThisPass = true;
               totalFixed++;
            }
         }
         // Collapse any newly created 'jmp __breturn' directly into 'rts' (1 byte instead of 3 bytes)
         // and re-verify branch distances in the next in-memory pass if anything changed
         if(new Pass5DoubleJumpElimination(getProgram()).optimize()) {
            anyFixedThisPass = true;
         }
      }

      return totalFixed;
   }

   private static int findNextNonComment(List<LineRef> allLines, int start) {
      for(int i = start; i < allLines.size(); i++) {
         LineRef ref = allLines.get(i);
         if(ref.line instanceof AsmComment || ref.line instanceof AsmConstant || ref.line instanceof AsmLabelDecl) {
            continue;
         }
         return i;
      }
      return -1;
   }

   /**
    * Post-pass to relax (shorten) long branch trampolines back to 2-byte relative branches
    * across chunk boundaries if the target has come within [-120..+120] bytes.
    * Also folds inverted branches over single jmp instructions: bxx L; jmp TARGET; L: -> b!xx TARGET.
    */
   private int relaxShortBranches() {
      int totalRelaxed = 0;
      boolean anyRelaxed = true;
      while(anyRelaxed) {
         anyRelaxed = false;

         List<LineRef> allLines = new ArrayList<>();
         for(AsmChunk chunk : getAsmProgram().getChunks()) {
            for(AsmLine line : chunk.getLines()) {
               allLines.add(new LineRef(chunk, line));
            }
         }

         // Phase 1: Collect label offsets, segments, line indices, RTS instructions, and targeted labels per scope
         Map<String, Integer> labelOffsets = new HashMap<>();
         Map<String, String> labelSegments = new HashMap<>();
         Map<String, Integer> labelLineIndices = new HashMap<>();
         Map<String, List<RtsRef>> scopeRts = new HashMap<>();
         Map<AsmLine, RtsRef> rtsByLine = new HashMap<>();
         Set<String> targetedLabels = new HashSet<>();
         int[] origLineBytes = new int[allLines.size()];
         String currentScope = null;
         String currentSegment = "Code";
         Map<String, Integer> segmentOffsets = new HashMap<>();
         String lastNonAnonLabel = null;

         for(int k = 0; k < allLines.size(); k++) {
            LineRef ref = allLines.get(k);
            origLineBytes[k] = ref.line.getLineBytes();
            if(ref.line instanceof AsmInstruction) {
               AsmInstruction instr = (AsmInstruction) ref.line;
               String target = instr.getOperandJumpTarget();
               if(target != null) targetedLabels.add(target);
               String op1 = instr.getOperand1();
               if(op1 != null && !op1.startsWith("#") && !op1.startsWith("<") && !op1.startsWith(">")) {
                  targetedLabels.add(op1);
               }
            }
         }

         for(int lIdx = 0; lIdx < allLines.size(); lIdx++) {
            LineRef ref = allLines.get(lIdx);
            AsmLine line = ref.line;
            if(line instanceof AsmScopeBegin) {
               currentScope = ((AsmScopeBegin) line).getLabel();
               currentSegment = "Code";
               segmentOffsets.clear();
               lastNonAnonLabel = null;
            } else if(line instanceof AsmScopeEnd) {
               currentScope = null;
               currentSegment = "Code";
               segmentOffsets.clear();
               lastNonAnonLabel = null;
            } else if(currentScope != null) {
               if(line instanceof AsmSegment) {
                  String seg = ((AsmSegment) line).getName();
                  currentSegment = (seg != null) ? seg : "Code";
               } else {
                  int currentOffset = segmentOffsets.getOrDefault(currentSegment, 0);
                  if(line instanceof AsmLabel) {
                     String label = ((AsmLabel) line).getLabel();
                     if(!label.startsWith("!")) {
                        String key = currentScope + "::" + label;
                        labelOffsets.put(key, currentOffset);
                        labelSegments.put(key, currentSegment);
                        labelLineIndices.put(key, lIdx);
                        lastNonAnonLabel = label;
                     }
                  } else if(line instanceof AsmInstruction) {
                     AsmInstruction instr = (AsmInstruction) line;
                     if(instr.getCpuOpcode() != null && "rts".equals(instr.getCpuOpcode().getMnemonic())) {
                        boolean isPartTrampoline = false;
                        for(int prevIdx = lIdx - 1; prevIdx >= 0; prevIdx--) {
                           AsmLine prevLine = allLines.get(prevIdx).line;
                           if(prevLine instanceof AsmComment) continue;
                           if(prevLine instanceof AsmInstruction) {
                              AsmInstruction prevInstr = (AsmInstruction) prevLine;
                              if(prevInstr.getCpuOpcode() != null && invertBranch(prevInstr.getCpuOpcode()) != null) {
                                 String bTarget = prevInstr.getOperandJumpTarget();
                                 if(bTarget != null && bTarget.startsWith("!")) {
                                    isPartTrampoline = true;
                                 }
                              }
                           }
                           break;
                        }
                        RtsRef rtsRef = new RtsRef(ref, currentOffset, currentSegment, lIdx, lastNonAnonLabel, !isPartTrampoline);
                        if(lastNonAnonLabel != null && targetedLabels.contains(lastNonAnonLabel)) {
                           rtsRef.isTargeted = true;
                        }
                        scopeRts.computeIfAbsent(currentScope, k -> new ArrayList<>()).add(rtsRef);
                        rtsByLine.put(line, rtsRef);
                     }
                     lastNonAnonLabel = null;
                  }
                  segmentOffsets.put(currentSegment, currentOffset + origLineBytes[lIdx]);
               }
            }
         }

         // Phase 2: Find and relax non-overlapping trampolines across chunk boundaries in a single sweep
         currentScope = null;
         currentSegment = "Code";
         segmentOffsets.clear();

         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref1 = allLines.get(i);
            AsmLine line1 = ref1.line;

            if(line1 instanceof AsmScopeBegin) {
               currentScope = ((AsmScopeBegin) line1).getLabel();
               currentSegment = "Code";
               segmentOffsets.clear();
            } else if(line1 instanceof AsmScopeEnd) {
               currentScope = null;
               currentSegment = "Code";
               segmentOffsets.clear();
            } else if(currentScope != null) {
               if(line1 instanceof AsmSegment) {
                  String seg = ((AsmSegment) line1).getName();
                  currentSegment = (seg != null) ? seg : "Code";
                  continue;
               }
               int currentOffset = segmentOffsets.getOrDefault(currentSegment, 0);
               if(line1 instanceof AsmInstruction) {
                  AsmInstruction instr1 = (AsmInstruction) line1;
                  CpuOpcode inv1 = invertBranch(instr1.getCpuOpcode());
                  if(inv1 != null) {
                     int idx2 = findNextInstruction(allLines, i + 1);
                     int idx3 = (idx2 != -1) ? findNextNonComment(allLines, idx2 + 1) : -1;

                     if(idx2 != -1 && idx3 != -1) {
                        LineRef ref2 = allLines.get(idx2);
                        LineRef ref3 = allLines.get(idx3);

                        if(ref2.line instanceof AsmInstruction && ref3.line instanceof AsmLabel) {
                           AsmInstruction instr2 = (AsmInstruction) ref2.line;
                           AsmLabel label3 = (AsmLabel) ref3.line;
                           boolean relaxed = false;

                           if(instr2.getCpuOpcode() != null
                                 && "jmp".equals(instr2.getCpuOpcode().getMnemonic())
                                 && instr2.getCpuOpcode().getAddressingMode() == CpuAddressingMode.ABS) {
                              String branchTarget = instr1.getOperandJumpTarget();
                              String jmpTarget = instr2.getOperand1();
                              String fallthroughLabel = label3.getLabel();

                              // Pattern 1: KickC Trampoline [b!xx !L+, jmp TARGET, !L:]
                              boolean isTrampoline = (branchTarget != null && fallthroughLabel != null && jmpTarget != null &&
                                 fallthroughLabel.startsWith("!") && fallthroughLabel.length() > 2 &&
                                 !fallthroughLabel.contains("+") && !fallthroughLabel.contains("-") &&
                                 branchTarget.equals(fallthroughLabel + "+") &&
                                 !jmpTarget.startsWith("!"));

                              if(isTrampoline && jmpTarget != null) {
                                 String key = currentScope + "::" + jmpTarget;
                                 Integer targetOffset = labelOffsets.get(key);
                                 Integer targetLineIdx = labelLineIndices.get(key);
                                 if(targetOffset != null && currentSegment.equals(labelSegments.get(key))) {
                                    int branchPc = currentOffset + 2;
                                    int distance = targetOffset - branchPc;
                                    if(isSafeRelaxDistance(allLines, i, targetLineIdx, distance)) {
                                       getLog().append("Relaxing long branch trampoline to " + inv1.getMnemonic() + " " + jmpTarget);
                                       instr1.setCpuOpcode(inv1);
                                       instr1.setOperandJumpTarget(jmpTarget);
                                       tombstoneLine(allLines, idx2);
                                       tombstoneLine(allLines, idx3);
                                       anyRelaxed = true;
                                       totalRelaxed++;
                                       relaxed = true;
                                    }
                                 }
                              }

                              // Pattern 2: Inverted branch over jmp [bxx L, jmp TARGET, L:]
                              boolean isInvertedBranch = !relaxed && (branchTarget != null && fallthroughLabel != null && jmpTarget != null &&
                                 !branchTarget.startsWith("!") && !fallthroughLabel.startsWith("!") && !jmpTarget.startsWith("!") &&
                                 branchTarget.equals(fallthroughLabel));

                              if(isInvertedBranch && jmpTarget != null) {
                                 String key = currentScope + "::" + jmpTarget;
                                 Integer targetOffset = labelOffsets.get(key);
                                 Integer targetLineIdx = labelLineIndices.get(key);
                                 if(targetOffset != null && currentSegment.equals(labelSegments.get(key))) {
                                    int branchPc = currentOffset + 2;
                                    int distance = targetOffset - branchPc;
                                    if(isSafeRelaxDistance(allLines, i, targetLineIdx, distance)) {
                                       getLog().append("Folding inverted branch over jmp to " + inv1.getMnemonic() + " " + jmpTarget);
                                       instr1.setCpuOpcode(inv1);
                                       instr1.setOperandJumpTarget(jmpTarget);
                                       tombstoneLine(allLines, idx2);
                                       anyRelaxed = true;
                                       totalRelaxed++;
                                       relaxed = true;
                                    }
                                 }
                              }
                           } else if(instr2.getCpuOpcode() != null && "rts".equals(instr2.getCpuOpcode().getMnemonic())) {
                              String branchTarget = instr1.getOperandJumpTarget();
                              String fallthroughLabel = label3.getLabel();

                              boolean isReturnTrampoline = (branchTarget != null && fallthroughLabel != null &&
                                 fallthroughLabel.startsWith("!") && fallthroughLabel.length() > 2 &&
                                 !fallthroughLabel.contains("+") && !fallthroughLabel.contains("-") &&
                                 branchTarget.equals(fallthroughLabel + "+"));

                              if(isReturnTrampoline) {
                                 RtsRef ref2Rts = rtsByLine.get(ref2.line);
                                 boolean rtsIsTargeted = (ref2Rts != null && ref2Rts.isTargeted);
                                 List<RtsRef> currentScopeRtsList = scopeRts.get(currentScope);

                                 if(!rtsIsTargeted) {
                                    // Sub-case 1: Check if followed by jmp TARGET
                                    int idx4 = findNextInstruction(allLines, idx3 + 1);
                                    if(idx4 != -1) {
                                       LineRef ref4 = allLines.get(idx4);
                                       if(ref4.line instanceof AsmInstruction) {
                                          AsmInstruction instr4 = (AsmInstruction) ref4.line;
                                          if(instr4.getCpuOpcode() != null
                                                && "jmp".equals(instr4.getCpuOpcode().getMnemonic())
                                                && instr4.getCpuOpcode().getAddressingMode() == CpuAddressingMode.ABS) {
                                             String jmpTarget = instr4.getOperand1();
                                             if(jmpTarget != null && !jmpTarget.startsWith("!")) {
                                                String key = currentScope + "::" + jmpTarget;
                                                Integer targetOffset = labelOffsets.get(key);
                                                Integer targetLineIdx = labelLineIndices.get(key);
                                                if(targetOffset != null && Objects.equals(currentSegment, labelSegments.get(key))) {
                                                   int branchPc = currentOffset + 2;
                                                   int distance = targetOffset - branchPc;
                                                   if(isSafeRelaxDistance(allLines, i, targetLineIdx, distance)) {
                                                      getLog().append("Relaxing branch-rts-jmp to " + instr1.getCpuOpcode().getMnemonic() + " " + jmpTarget + " and rts");
                                                      instr1.setOperandJumpTarget(jmpTarget);
                                                      tombstoneLine(allLines, idx3);
                                                      tombstoneLine(allLines, idx4);
                                                      anyRelaxed = true;
                                                      totalRelaxed++;
                                                      relaxed = true;
                                                   }
                                                }
                                             }
                                          }
                                       }
                                    }

                                    // Sub-case 2: Relax return trampoline to __breturn
                                    if(!relaxed) {
                                       String breturnKey = currentScope + "::__breturn";
                                       Integer breturnOffset = labelOffsets.get(breturnKey);
                                       Integer breturnLineIdx = labelLineIndices.get(breturnKey);
                                       if(breturnOffset != null && Objects.equals(currentSegment, labelSegments.get(breturnKey))) {
                                          int branchPc = currentOffset + 2;
                                          int distance = breturnOffset - branchPc;
                                          if(isSafeRelaxDistance(allLines, i, breturnLineIdx, distance)) {
                                             getLog().append("Relaxing return trampoline to " + inv1.getMnemonic() + " __breturn");
                                             instr1.setCpuOpcode(inv1);
                                             instr1.setOperandJumpTarget("__breturn");
                                             tombstoneLine(allLines, idx2);
                                             tombstoneLine(allLines, idx3);
                                             if(ref2Rts != null) ref2Rts.isRemoved = true;
                                             if(currentScopeRtsList != null) {
                                                for(RtsRef r : currentScopeRtsList) {
                                                   if("__breturn".equals(r.label)) {
                                                      r.isTargeted = true;
                                                   }
                                                }
                                             }
                                             targetedLabels.add("__breturn");
                                             anyRelaxed = true;
                                             totalRelaxed++;
                                             relaxed = true;
                                          }
                                       }
                                    }

                                    // Sub-case 3: Relax return trampoline to another RTS in currentScope
                                    if(!relaxed && currentScopeRtsList != null) {
                                       RtsRef bestRts = null;
                                       int bestDist = 999999;
                                       for(RtsRef r : currentScopeRtsList) {
                                          if(r.isRemoved || r.ref.line == ref2.line) continue;
                                          if(!Objects.equals(currentSegment, r.segment)) continue;
                                          int branchPc = currentOffset + 2;
                                          int dist = r.offset - branchPc;
                                          if(isSafeRelaxDistance(allLines, i, r.lineIdx, dist)) {
                                             if(r.isStandalone && Math.abs(dist) < Math.abs(bestDist)) {
                                                bestDist = dist;
                                                bestRts = r;
                                             }
                                          }
                                       }
                                       if(bestRts == null) {
                                          for(RtsRef r : currentScopeRtsList) {
                                             if(r.isRemoved || r.ref.line == ref2.line) continue;
                                             if(!Objects.equals(currentSegment, r.segment)) continue;
                                             int branchPc = currentOffset + 2;
                                             int dist = r.offset - branchPc;
                                             if(dist > 0 && isSafeRelaxDistance(allLines, i, r.lineIdx, dist)) {
                                                if(Math.abs(dist) < Math.abs(bestDist)) {
                                                   bestDist = dist;
                                                   bestRts = r;
                                                }
                                             }
                                          }
                                       }

                                       if(bestRts != null) {
                                          String targetLabel = bestRts.label;
                                          if(targetLabel == null) {
                                             targetLabel = "__rts_" + (++retLabelCounter);
                                             bestRts.label = targetLabel;
                                             bestRts.ref.chunk.addLineBefore(bestRts.ref.line, new AsmLabel(targetLabel));
                                          }
                                          bestRts.isTargeted = true;
                                          getLog().append("Relaxing return trampoline to " + inv1.getMnemonic() + " " + targetLabel);
                                          instr1.setCpuOpcode(inv1);
                                          instr1.setOperandJumpTarget(targetLabel);
                                          tombstoneLine(allLines, idx2);
                                          tombstoneLine(allLines, idx3);
                                          if(ref2Rts != null) ref2Rts.isRemoved = true;
                                          anyRelaxed = true;
                                          totalRelaxed++;
                                       }
                                    }
                                 }
                              }
                           }
                        }
                     }
                  }
               }
               segmentOffsets.put(currentSegment, currentOffset + origLineBytes[i]);
            }
         }
      }
      return totalRelaxed;
   }

   /**
    * CF-01: Conservatively verify that relaxing a trampoline across [fromIdx..toIdx] cannot exceed
    * relative branch range due to alignment directives or inline KickAsm blocks whose getLineBytes() is 0.
    */
   private boolean isSafeRelaxDistance(List<LineRef> allLines, int fromIdx, Integer toIdx, int distance) {
      if(Math.abs(distance) > 120) {
         return false;
      }
      if(toIdx == null) {
         return true;
      }
      int lo = Math.min(fromIdx, toIdx);
      int hi = Math.max(fromIdx, toIdx);
      int extraPadding = 0;
      for(int k = lo + 1; k < hi; k++) {
         AsmLine mid = allLines.get(k).line;
         if(mid instanceof AsmLoopAlign loopAlign) {
            extraPadding += Math.max(0, loopAlign.getLoopLength() - 1);
         } else if(mid instanceof AsmDataAlignment || mid instanceof AsmInlineKickAsm) {
            return false;
         }
      }
      return (Math.abs(distance) + extraPadding) <= 120;
   }

   /**
    * Detect if any branch distance is to long, and fix it by rewriting the branch if it is.
    *
    * @return true if any branch was rewritten
    */
   private boolean step() {
      // Reindex ASM lines
      new Pass5ReindexAsmLines(getProgram()).optimize();
      long t0 = System.currentTimeMillis();
      Path tmpDir = TmpDirManager.MANAGER.newTmpDir();
      // Generate the ASM file
      try {
         final Path asmFilePath = getProgram().getOutputFileManager().getOutputFile(tmpDir, getProgram().getOutputFileManager().getAsmExtension());
         writeOutputFile(asmFilePath, getProgram().getAsm().toString(new AsmProgram.AsmPrintState(false), null));
         // Copy Resource Files
         for(Path asmResourceFile : getProgram().getAsmResourceFiles()) {
            File binFile = getTmpFile(tmpDir, asmResourceFile.getFileName().toString());
            if(Files.exists(asmResourceFile)) {
               Files.copy(asmResourceFile, binFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
         }
      } catch(IOException e) {
         throw new CompileError("Error writing ASM temp file: " + e.getMessage(), e);
      }
      long tWrite = System.currentTimeMillis() - t0;

      // Compile using KickAssembler - catch the output in a String
      long t1 = System.currentTimeMillis();
      File asmFile = getProgram().getOutputFileManager().getOutputFile(tmpDir, getProgram().getOutputFileManager().getAsmExtension()).toFile();
      File binaryFile = getProgram().getOutputFileManager().getOutputFile(tmpDir, getProgram().getOutputFileManager().getBinaryExtension()).toFile();
      ByteArrayOutputStream kickAssOut = new ByteArrayOutputStream();
      System.setOut(new PrintStream(kickAssOut));
      int asmRes = -1;
      try {
         CharToPetsciiConverter.setCurrentEncoding("screencode_mixed");
         asmRes = KickAssembler65CE02.main2(new String[]{asmFile.getAbsolutePath(), "-o", binaryFile.getAbsolutePath()});
      } catch(Throwable e) {
         if(e instanceof AssertionError && e.getMessage().contains("Invalid number of bytes in memblock!")) {
            throw new CompileError("Error! KickAssembler failed due to assertion. Please run java without -ea / -enableassertions.", e);
         } else {
            throw new CompileError("Error! KickAssembler failed, while trying to fix long branch. " + e);
         }
      } finally {
         System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out)));
      }
      String output = kickAssOut.toString();
      long tKasm = System.currentTimeMillis() - t1;
      if(getLog().isVerboseFixLongBranch()) {
         System.out.println(String.format("         (writeAsm=%4.2fs, kasm=%4.2fs, res=%d)", tWrite / 1000.0, tKasm / 1000.0, asmRes));
      }

      if(getLog().isVerboseFixLongBranch())
         getLog().append("Pass5FixLongBranches: ASM process result " + asmRes);

      // Look for a long branch distance error
      if(asmRes != 0) {
         String outputLines[] = output.split("\\r?\\n");
         List<Integer> linesToFix = new ArrayList<>();
         for(int i = 0; i < outputLines.length; i++) {
            String outputLine = outputLines[i];
            if(outputLine.contains("Error: relative address is illegal (jump distance is too far).")) {
               // Found a long branch!
               if(i + 1 < outputLines.length) {
                  String contextLine = outputLines[i + 1];
                  Pattern contextPattern = Pattern.compile("at line ([0-9]*),.*");
                  Matcher matcher = contextPattern.matcher(contextLine);
                  if(matcher.matches()) {
                     int contextLineIdx = Integer.parseInt(matcher.group(1));
                     linesToFix.add(contextLineIdx - 1);
                  }
               }
            }
         }
         if(!linesToFix.isEmpty()) {
            // Fix in descending order to avoid line offset invalidation
            linesToFix.sort(Collections.reverseOrder());
            boolean fixedAny = false;
            for(int lineIdx : linesToFix) {
               if(fixLongBranch(lineIdx)) {
                  fixedAny = true;
               }
            }
            if(fixedAny) {
               return true;
            }
         }
         // KickAssembler returned error, but no long branch was found. Fail with the output from KickAss
         throw new CompileError("Error! Failed to compile using KickAss\n" + output);
      }
      return false;
   }

   /**
    * Fix a long branch on a specific instruction within a chunk.
    */
   private boolean fixLongBranch(AsmChunk asmChunk, AsmInstruction asmInstruction) {
      CpuOpcode cpuOpcode = asmInstruction.getCpuOpcode();
      CpuOpcode inverseCpuOpcode = invertBranch(cpuOpcode);
      if(inverseCpuOpcode != null) {
         if(getLog().isVerboseFixLongBranch()) {
            getLog().append("Fixing long branch [" + asmInstruction.getIndex() + "] " + asmInstruction.toString() + " to " + inverseCpuOpcode.getMnemonic());
         }
         String branchDest = asmInstruction.getOperandJumpTarget();
         if(branchDest == null || branchDest.startsWith("!")) {
            return false;
         }
         asmInstruction.setCpuOpcode(inverseCpuOpcode);
         String newLabel = AsmFormat.asmFix("!" + branchDest);
         asmInstruction.setOperandJumpTarget(newLabel + "+");
         CpuOpcode jmpOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("jmp", CpuAddressingMode.ABS, false);
         AsmInstruction jmpInstruction = new AsmInstruction(jmpOpcode, branchDest, null, null);
         asmChunk.addLineAfter(asmInstruction, jmpInstruction);
         asmChunk.addLineAfter(jmpInstruction, new AsmLabel(newLabel));
         return true;
      }
      return false;
   }

   /**
    * Fix a long branch detected at a specific ASM index
    *
    * @param idx The index of the ASM line with the long branch
    * @return True if the branch was fixed
    */
   private boolean fixLongBranch(int idx) {
      AsmProgram asm = getProgram().getAsm();
      AsmChunk asmChunk = asm.getAsmChunk(idx);
      if(asmChunk != null) {
         AsmLine asmLine = asmChunk.getAsmLine(idx);
         if(asmLine instanceof AsmInstruction) {
            return fixLongBranch(asmChunk, (AsmInstruction) asmLine);
         }
      }
      return false;
   }

   private CpuOpcode invertBranch(CpuOpcode cpuOpcode) {
      switch(cpuOpcode.getMnemonic()) {
         case "bcc":
            return getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("bcs", CpuAddressingMode.REL, false);
         case "bcs":
            return getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("bcc", CpuAddressingMode.REL, false);
         case "beq":
            return getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("bne", CpuAddressingMode.REL, false);
         case "bne":
            return getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("beq", CpuAddressingMode.REL, false);
         case "bpl":
            return getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("bmi", CpuAddressingMode.REL, false);
         case "bmi":
            return getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("bpl", CpuAddressingMode.REL, false);
         case "bvs":
            return getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("bvc", CpuAddressingMode.REL, false);
         case "bvc":
            return getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("bvs", CpuAddressingMode.REL, false);
         default:
            return null;
      }
   }

   private File writeOutputFile(Path outputFile, String outputString) throws IOException {
      File file = outputFile.toFile();
      FileOutputStream outputStream = new FileOutputStream(file);
      OutputStreamWriter writer = new OutputStreamWriter(outputStream);
      writer.write(outputString);
      writer.close();
      outputStream.close();
      return file;
   }

   private static File getTmpFile(Path tmpDir, String fileName) {
      return new File(tmpDir.toFile(), fileName);
   }

}
