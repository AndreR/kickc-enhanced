package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;

import java.util.*;

/**
 * Assembler Peephole Optimization: Redundant $FE/$FF Pointer Reload Elimination.
 * Eliminates redundant reloads of 16-bit pointers into $fe/$ff across statement boundaries
 * within basic blocks.
 * Covers:
 * 1. ldy ptr; sty.z $fe; ldy ptr+1; sty.z $ff (with optional sty $fd / ldy $fd wrapping)
 * 2. lda ptr; sta.z $fe; lda ptr+1; sta.z $ff
 * 3. Mixed / indexed / transfer forms
 */
public class Pass5PointerReloadElimination extends Pass5AsmOptimization {

   public Pass5PointerReloadElimination(Program program) {
      super(program);
   }

   private static boolean isZpFe(String op) {
      return isZpAddressOperand(op, "$fe") || (op != null && op.trim().equals("254"));
   }

   private static boolean isZpFf(String op) {
      return isZpAddressOperand(op, "$ff") || (op != null && op.trim().equals("255"));
   }

   private static boolean isZpFd(String op) {
      return isZpAddressOperand(op, "$fd") || (op != null && op.trim().equals("253"));
   }

   @Override
   public boolean optimize() {
      boolean overallModified = false;
      boolean passModified = true;

      while(passModified) {
         passModified = false;

         List<LineRef> allLines = flattenLines(getAsmProgram());
         Map<String, CanonicalResolver> scopeResolvers = buildScopeResolvers(allLines);

         // ZP-01: Pre-scan back-edges and nooptimize labels per scope so forward CFG-meet only applies to DAG join labels
         Set<String> seenLabelsInScan = new HashSet<>();
         Set<String> backEdgeOrUnsafeLabels = new HashSet<>();
         for(LineRef r : allLines) {
            if(r.line instanceof AsmScopeBegin || r.line instanceof AsmScopeEnd) {
               seenLabelsInScan.clear();
            } else if(r.line instanceof AsmLabel lbl) {
               String key = r.scope + "::" + lbl.getLabel();
               seenLabelsInScan.add(key);
               if(lbl.isNoOptimize() || (lbl.getLabel() != null && lbl.getLabel().startsWith("!"))) {
                  backEdgeOrUnsafeLabels.add(key);
               }
            } else if(r.line instanceof AsmInstruction inst && inst.getCpuOpcode() != null && inst.getCpuOpcode().isJump()) {
               String target = inst.getOperandJumpTarget();
               if(target != null) {
                  String key = r.scope + "::" + target;
                  if(seenLabelsInScan.contains(key)) {
                     backEdgeOrUnsafeLabels.add(key);
                  }
               }
            }
         }

         Map<String, String[]> incomingFeFf = new HashMap<>();
         boolean prevWasUncondTerminator = false;

         String currentFe = null;
         String currentFf = null;
         String currentA = null;
         String currentY = null;
         String derefY = null;
         boolean derefValid = false;

         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref = allLines.get(i);
            CanonicalResolver resolver = scopeResolvers.getOrDefault(ref.scope, scopeResolvers.get(""));

            // Scope / Inline boundaries reset everything
            if(ref.line instanceof AsmScopeBegin || ref.line instanceof AsmScopeEnd || ref.line instanceof AsmInlineKickAsm) {
               currentFe = null;
               currentFf = null;
               currentA = null;
               currentY = null;
               derefValid = false;
               prevWasUncondTerminator = false;
               incomingFeFf.clear();
               continue;
            }

            // ZP-01: Forward CFG-Meet (intersection) of ($fe, $ff) at named labels
            if(ref.line instanceof AsmLabel lbl) {
               String key = ref.scope + "::" + lbl.getLabel();
               if(!backEdgeOrUnsafeLabels.contains(key)) {
                  String[] incoming = incomingFeFf.remove(key);
                  if(prevWasUncondTerminator) {
                     if(incoming != null) {
                        currentFe = incoming[0];
                        currentFf = incoming[1];
                     } else {
                        currentFe = null;
                        currentFf = null;
                     }
                  } else if(incoming != null) {
                     currentFe = samePointerByte(currentFe, incoming[0], resolver) ? currentFe : null;
                     currentFf = samePointerByte(currentFf, incoming[1], resolver) ? currentFf : null;
                  }
                  // If !prevWasUncondTerminator && incoming == null: label is only entered by fallthrough -> keep currentFe/currentFf!
               } else {
                  currentFe = null;
                  currentFf = null;
               }
               currentA = null;
               currentY = null;
               derefValid = false;
               prevWasUncondTerminator = false;
               continue;
            }

            if(ref.line instanceof AsmComment || ref.line instanceof AsmConstant || ref.line instanceof AsmLabelDecl) {
               continue;
            }

            if(!(ref.line instanceof AsmInstruction)) {
               currentFe = null;
               currentFf = null;
               currentA = null;
               prevWasUncondTerminator = false;
               continue;
            }

            AsmInstruction inst = (AsmInstruction) ref.line;
            CpuOpcode op = inst.getCpuOpcode();
            if(op == null || inst.isNoOptimize()) {
               currentFe = null;
               currentFf = null;
               currentA = null;
               prevWasUncondTerminator = false;
               continue;
            }

            String mnem = op.getMnemonic();
            CpuAddressingMode mode = op.getAddressingMode();

            // ZP-01: Record outgoing ($fe, $ff) state along forward branches/jumps
            if(op.isJump() && !mnem.equals("jsr")) {
               String target = inst.getOperandJumpTarget();
               if(target != null && !target.startsWith("!")) {
                  String key = ref.scope + "::" + target;
                  if(!backEdgeOrUnsafeLabels.contains(key)) {
                     String[] existing = incomingFeFf.get(key);
                     if(existing == null) {
                        incomingFeFf.put(key, new String[]{currentFe, currentFf});
                     } else {
                        existing[0] = samePointerByte(existing[0], currentFe, resolver) ? existing[0] : null;
                        existing[1] = samePointerByte(existing[1], currentFf, resolver) ? existing[1] : null;
                     }
                  }
               }
            }

            // Control flow breaks (unconditional jump, subroutine call, returns) reset tracking
            if(mnem.equals("jmp") || mnem.equals("jsr") || mnem.equals("rts") || mnem.equals("rti")) {
               currentFe = null;
               currentFf = null;
               currentA = null;
               currentY = null;
               derefValid = false;
               prevWasUncondTerminator = !mnem.equals("jsr");
               continue;
            }
            prevWasUncondTerminator = false;

            // Track A register value for high-byte after 16-bit additions and invalidate deref on A modification
            if(mnem.equals("lda") || mnem.equals("adc") || mnem.equals("sbc")) {
               currentA = inst.getOperand1();
               if(!mode.equals(CpuAddressingMode.IZY)) {
                  derefValid = false;
               }
            } else if(mnem.equals("pla") || mnem.equals("txa") || mnem.equals("tya") ||
                      mnem.equals("and") || mnem.equals("ora") || mnem.equals("eor") ||
                      mnem.equals("asl") || mnem.equals("lsr") || mnem.equals("rol") || mnem.equals("ror") ||
                      mnem.equals("alr") || mnem.equals("anc") || mnem.equals("arr") || mnem.equals("axs") ||
                      mnem.equals("dcp") || mnem.equals("isc") || mnem.equals("rla") || mnem.equals("rra") ||
                      mnem.equals("slo") || mnem.equals("sre")) {
               currentA = null;
               derefValid = false;
            }

            // Track Y register changes (ZP-02: eliminate redundant LDY #imm before any IZY instruction)
            if(mnem.equals("ldy") && mode.equals(CpuAddressingMode.IMM) && !inst.isNoOptimize()) {
               if(currentY != null && AsmProgramStaticRegisterValues.matchImm(currentY, inst.getOperand1())) {
                  int nextIdx = findNextInstruction(allLines, i + 1);
                  if(nextIdx != -1) {
                     AsmInstruction nextInst = (AsmInstruction) allLines.get(nextIdx).line;
                     if(nextInst.getCpuOpcode() != null &&
                        nextInst.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.IZY)) {
                        getLog().append("Eliminating redundant LDY " + inst.getOperand1() + " (Y already contains " + currentY + ")");
                        tombstoneLine(allLines, i);
                        passModified = true;
                        overallModified = true;
                        continue;
                     }
                  }
               }
               currentY = inst.getOperand1();
            } else if(mnem.equals("iny") || mnem.equals("dey") || mnem.equals("tay") || mnem.equals("ply") ||
                      (mnem.equals("ldy") && !mode.equals(CpuAddressingMode.IMM))) {
               currentY = null;
               derefValid = false;
            }

            // Track / Eliminate redundant indirect LDA ($fe),y
            if(mnem.equals("lda") && mode.equals(CpuAddressingMode.IZY) && isZpFe(inst.getOperand1()) && !inst.isNoOptimize()) {
               boolean volOrHwPtr = isPointerToVolatileOrHardware(currentFe, currentFf, ref.scope, resolver, getProgram());
               if(!volOrHwPtr) {
                  if(derefValid && currentY != null && currentY.equals(derefY)) {
                     getLog().append("Eliminating redundant LDA ($fe),y (A already contains dereferenced value)");
                     ref.chunk.getLines().remove(inst);
                     passModified = true;
                     overallModified = true;
                     break;
                  }
                  if(currentFe != null && currentFf != null && currentY != null) {
                     derefValid = true;
                     derefY = currentY;
                  }
               } else {
                  derefValid = false;
               }
            }

            // =========================================================
            // Pattern A (via Y):
            // Optional: [sty.z $fd] (immediately preceding)
            // inst1: ldy <lo>
            // inst2: sty.z $fe
            // inst3: ldy <hi> (or tay when currentA != null)
            // inst4: sty.z $ff
            // Optional: [ldy.z $fd] (immediately succeeding)
            // =========================================================
            if(mnem.equals("ldy") && !inst.isNoOptimize()) {
               int idx1 = i;
               int idx2 = findNextInstruction(allLines, idx1 + 1);
               if(idx2 != -1) {
                  AsmInstruction inst2 = (AsmInstruction) allLines.get(idx2).line;
                  if(inst2.getCpuOpcode() != null && inst2.getCpuOpcode().getMnemonic().equals("sty") &&
                     isZpFe(inst2.getOperand1()) && !inst2.isNoOptimize()) {
                     int idx3 = findNextInstruction(allLines, idx2 + 1);
                     if(idx3 != -1) {
                        AsmInstruction inst3 = (AsmInstruction) allLines.get(idx3).line;
                        String mnem3 = inst3.getCpuOpcode() != null ? inst3.getCpuOpcode().getMnemonic() : "";
                        boolean isHiLoad = (mnem3.equals("ldy") || (mnem3.equals("tay") && currentA != null)) && !inst3.isNoOptimize();
                        if(isHiLoad) {
                           int idx4 = findNextInstruction(allLines, idx3 + 1);
                           if(idx4 != -1) {
                              AsmInstruction inst4 = (AsmInstruction) allLines.get(idx4).line;
                              if(inst4.getCpuOpcode() != null && inst4.getCpuOpcode().getMnemonic().equals("sty") &&
                                 isZpFf(inst4.getOperand1()) && !inst4.isNoOptimize()) {

                                 String lo = inst.getOperand1();
                                 String hi = mnem3.equals("tay") ? currentA : inst3.getOperand1();

                                 // Check for optional sty.z $fd immediately before idx1
                                 int preFd = -1;
                                 int prevIdx = findPrevInstruction(allLines, idx1 - 1);
                                 if(prevIdx != -1) {
                                    AsmInstruction prevInst = (AsmInstruction) allLines.get(prevIdx).line;
                                    if(prevInst.getCpuOpcode() != null && prevInst.getCpuOpcode().getMnemonic().equals("sty") &&
                                       isZpFd(prevInst.getOperand1()) && !prevInst.isNoOptimize()) {
                                       preFd = prevIdx;
                                    }
                                 }

                                 // Check for optional ldy.z $fd immediately after idx4
                                 int postFd = -1;
                                 int nextIdx = findNextInstruction(allLines, idx4 + 1);
                                 if(nextIdx != -1) {
                                    AsmInstruction nextInst = (AsmInstruction) allLines.get(nextIdx).line;
                                    if(nextInst.getCpuOpcode() != null && nextInst.getCpuOpcode().getMnemonic().equals("ldy") &&
                                       isZpFd(nextInst.getOperand1()) && !nextInst.isNoOptimize()) {
                                       postFd = nextIdx;
                                    }
                                 }

                                 // ZP-03: Canonical & half-pointer matching for Pattern A (via Y)
                                 boolean feMatch = samePointerByte(currentFe, lo, resolver);
                                 boolean ffMatch = samePointerByte(currentFf, hi, resolver);
                                 boolean hasFdWrap = (preFd != -1 && postFd != -1);
                                 boolean ySafeAfter = hasFdWrap || (postFd != -1) || isYOverwrittenAfter(allLines, idx4 + 1);
                                 if(feMatch && ffMatch && ySafeAfter) {
                                    getLog().append("Eliminating redundant pointer reload: " + lo + " into $fe/$ff");
                                    if(hasFdWrap) {
                                       tombstoneLine(allLines, preFd);
                                       tombstoneLine(allLines, postFd);
                                    }
                                    tombstoneLine(allLines, idx1);
                                    tombstoneLine(allLines, idx2);
                                    tombstoneLine(allLines, idx3);
                                    tombstoneLine(allLines, idx4);
                                    passModified = true;
                                    overallModified = true;
                                    i = (postFd != -1 ? postFd : idx4);
                                    continue;
                                 } else if(ffMatch && ySafeAfter && (getLiveFlagsAfter(allLines, idx4, ref.scope, getProgram()) & FLAG_NZ) == 0) {
                                    getLog().append("Eliminating redundant high-byte Y pointer reload into $ff: " + hi);
                                    tombstoneLine(allLines, idx3);
                                    tombstoneLine(allLines, idx4);
                                    currentFe = lo;
                                    passModified = true;
                                    overallModified = true;
                                    i = (postFd != -1 ? postFd : idx4);
                                    continue;
                                 } else if(feMatch && !inst3.getCpuOpcode().getMnemonic().equals("tay")) {
                                    getLog().append("Eliminating redundant low-byte Y pointer reload into $fe: " + lo);
                                    tombstoneLine(allLines, idx1);
                                    tombstoneLine(allLines, idx2);
                                    currentFf = hi;
                                    passModified = true;
                                    overallModified = true;
                                    i = (postFd != -1 ? postFd : idx4);
                                    continue;
                                 } else {
                                    // FIRST load of this pointer: Record in cache
                                    currentFe = lo;
                                    currentFf = hi;
                                    // Advance i past the sequence so sub-instructions are not re-processed
                                    i = (postFd != -1 ? postFd : idx4);
                                    continue;
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }

            // =========================================================
            // Pattern B (via A):
            // inst1: lda <lo>
            // inst2: sta.z $fe
            // inst3: lda <hi>
            // inst4: sta.z $ff
            // =========================================================
            if(mnem.equals("lda") && !inst.isNoOptimize()) {
               int aIdx1 = i;
               int aIdx2 = findNextInstruction(allLines, aIdx1 + 1);
               if(aIdx2 != -1) {
                  AsmInstruction aInst2 = (AsmInstruction) allLines.get(aIdx2).line;
                  if(aInst2.getCpuOpcode() != null && aInst2.getCpuOpcode().getMnemonic().equals("sta") &&
                     isZpFe(aInst2.getOperand1()) && !aInst2.isNoOptimize()) {
                     int aIdx3 = findNextInstruction(allLines, aIdx2 + 1);
                     if(aIdx3 != -1) {
                        AsmInstruction aInst3 = (AsmInstruction) allLines.get(aIdx3).line;
                        if(aInst3.getCpuOpcode() != null && aInst3.getCpuOpcode().getMnemonic().equals("lda") && !aInst3.isNoOptimize()) {
                           int aIdx4 = findNextInstruction(allLines, aIdx3 + 1);
                           if(aIdx4 != -1) {
                              AsmInstruction aInst4 = (AsmInstruction) allLines.get(aIdx4).line;
                              if(aInst4.getCpuOpcode() != null && aInst4.getCpuOpcode().getMnemonic().equals("sta") &&
                                 isZpFf(aInst4.getOperand1()) && !aInst4.isNoOptimize()) {
                                 String lo = inst.getOperand1();
                                 String hi = aInst3.getOperand1();

                                 // ZP-03: Canonical & half-pointer matching for Pattern B (via A)
                                 boolean feMatch = samePointerByte(currentFe, lo, resolver);
                                 boolean ffMatch = samePointerByte(currentFf, hi, resolver);
                                 if(feMatch && ffMatch) {
                                    if(isADeadAfter(allLines, aIdx4 + 1)) {
                                       getLog().append("Eliminating redundant LDA pointer reload: " + lo + " into $fe/$ff");
                                       tombstoneLine(allLines, aIdx1);
                                       tombstoneLine(allLines, aIdx2);
                                       tombstoneLine(allLines, aIdx3);
                                       tombstoneLine(allLines, aIdx4);
                                       passModified = true;
                                       overallModified = true;
                                       i = aIdx4;
                                       continue;
                                    }
                                 } else if(ffMatch && isADeadAfter(allLines, aIdx4 + 1) && (getLiveFlagsAfter(allLines, aIdx4, ref.scope, getProgram()) & FLAG_NZ) == 0) {
                                    getLog().append("Eliminating redundant high-byte LDA pointer reload into $ff: " + hi);
                                    tombstoneLine(allLines, aIdx3);
                                    tombstoneLine(allLines, aIdx4);
                                    currentFe = lo;
                                    passModified = true;
                                    overallModified = true;
                                    i = aIdx4;
                                    continue;
                                 } else if(feMatch) {
                                    getLog().append("Eliminating redundant low-byte LDA pointer reload into $fe: " + lo);
                                    tombstoneLine(allLines, aIdx1);
                                    tombstoneLine(allLines, aIdx2);
                                    currentFf = hi;
                                    passModified = true;
                                    overallModified = true;
                                    i = aIdx4;
                                    continue;
                                 } else {
                                    currentFe = lo;
                                    currentFf = hi;
                                    i = aIdx4;
                                    continue;
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }

            // =========================================================
            // Invalidation check on memory writes
            // =========================================================
            if(mnem.equals("sta") || mnem.equals("stx") || mnem.equals("sty") ||
               mnem.equals("inc") || mnem.equals("dec") || mnem.equals("asl") ||
               mnem.equals("lsr") || mnem.equals("rol") || mnem.equals("ror")) {

               // CRITICAL: Indirect ZP modes (IZY: ($zp),y, IZX: ($zp,x)) write THROUGH the pointer,
               // NOT to the pointer itself! The pointer in $fe/$ff remains completely intact!
               if(CpuAddressingMode.IZY.equals(mode) || CpuAddressingMode.IZX.equals(mode)) {
                  derefValid = false;
                  continue;
               }
               derefValid = false;

               String dest = inst.getOperand1();
               if(dest != null) {
                  String destTrim = dest.trim().toLowerCase();
                  if(isZpFd(destTrim)) {
                     // Writing to $fd does not affect $fe/$ff
                     continue;
                  }
                  // Direct write to ZP $fe or $ff (addressing mode ZP) overwrites the pointer
                  if(CpuAddressingMode.ZP.equals(mode) && (isZpFe(destTrim) || isZpFf(destTrim))) {
                     currentFe = null;
                     currentFf = null;
                     continue;
                  }
                  // Check if dest matches base variable of currentFe or currentFf (e.g. inc ptr / sta ptr)
                  String destBase = getBaseVar(destTrim);
                  String feBase = getBaseVar(currentFe);
                  String ffBase = getBaseVar(currentFf);
                  if(destBase != null && (destBase.equalsIgnoreCase(feBase) || destBase.equalsIgnoreCase(ffBase))) {
                     currentFe = null;
                     currentFf = null;
                  }
               }
            }
         }
      }

      return overallModified;
   }

   private static boolean samePointerByte(String a, String b, CanonicalResolver resolver) {
      if(a == null || b == null) return false;
      if(a.equals(b)) return true;
      if(AsmProgramStaticRegisterValues.matchImm(a, b)) return true;
      if(resolver != null) {
         String ra = resolver.resolve(a);
         String rb = resolver.resolve(b);
         if(!ra.isEmpty() && ra.equals(rb)) return true;
      }
      return false;
   }

   private static boolean isYOverwrittenAfter(List<LineRef> allLines, int startIdx) {
      for(int m = startIdx; m < allLines.size(); m++) {
         LineRef cand = allLines.get(m);
         if(cand.line instanceof AsmComment || cand.line instanceof AsmConstant || cand.line instanceof AsmLabelDecl) continue;
         if(cand.line instanceof AsmInstruction) {
            AsmInstruction inst = (AsmInstruction) cand.line;
            CpuOpcode op = inst.getCpuOpcode();
            if(op == null) return false;
            String mnem = op.getMnemonic();
            if(mnem.equals("ldy") || mnem.equals("tay") || mnem.equals("ply")) {
               return true;
            }
            if(mnem.equals("sty") || mnem.equals("tya") || mnem.equals("phy") || mnem.equals("cpy") ||
               mnem.equals("dey") || mnem.equals("iny") ||
               mnem.equals("jsr") || mnem.equals("rts") || mnem.equals("rti") || mnem.equals("jmp") || mnem.startsWith("b")) {
               return false;
            }
            continue;
         }
         break;
      }
      return false;
   }

   private static boolean isADeadAfter(List<LineRef> allLines, int startIdx) {
      for(int m = startIdx; m < allLines.size(); m++) {
         LineRef cand = allLines.get(m);
         if(cand.line instanceof AsmComment || cand.line instanceof AsmConstant || cand.line instanceof AsmLabelDecl) continue;
         if(cand.line instanceof AsmInstruction) {
            AsmInstruction inst = (AsmInstruction) cand.line;
            CpuOpcode op = inst.getCpuOpcode();
            if(op == null) return false;
            String mnem = op.getMnemonic();
            CpuAddressingMode mode = op.getAddressingMode();
            if(mnem.equals("lda") || mnem.equals("pla") || mnem.equals("txa") || mnem.equals("tya") || mnem.equals("lax")) {
               return true;
            }
            if(mnem.equals("sta") || mnem.equals("tax") || mnem.equals("tay") || mnem.equals("pha") ||
               mnem.equals("cmp") || mnem.equals("adc") || mnem.equals("sbc") || mnem.equals("and") ||
               mnem.equals("ora") || mnem.equals("eor") || mnem.equals("sax") ||
               mnem.equals("alr") || mnem.equals("anc") || mnem.equals("arr") || mnem.equals("axs") ||
               mnem.equals("dcp") || mnem.equals("isc") || mnem.equals("rla") || mnem.equals("rra") ||
               mnem.equals("slo") || mnem.equals("sre") ||
               ((mnem.equals("asl") || mnem.equals("lsr") || mnem.equals("rol") || mnem.equals("ror")) && CpuAddressingMode.NON.equals(mode)) ||
               mnem.equals("jsr") || mnem.equals("rts") || mnem.equals("rti") || mnem.equals("jmp") || mnem.startsWith("b")) {
               return false;
            }
            continue;
         }
         break;
      }
      return false;
   }
}
