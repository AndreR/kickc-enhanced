package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.symbols.Variable;

import java.util.*;

/**
 * Assembler Peephole Optimization: Unroll 2-4 Byte Small Struct / Union Assignment Loops.
 *
 * Small struct or union assignments (2..4 bytes) frequently emit compact memcpy loops:
 *
 * Pattern A (Indirect Y-indexed):
 *    ldy #0
 *  !:
 *    lda (src),y
 *    sta (dst),y
 *    iny
 *    cpy #N
 *    bne !-
 *
 * Unrolled:
 *    ldy #0
 *    lda (src),y
 *    sta (dst),y
 *    iny
 *    lda (src),y
 *    sta (dst),y
 *    ... (N times)
 *
 * Pattern B (Forward Absolute X or Y-indexed):
 *    ldx #0
 *  !:
 *    lda src,x
 *    sta dst,x
 *    inx
 *    cpx #N
 *    bne !-
 *
 * Unrolled:
 *    lda src
 *    sta dst
 *    lda src+1
 *    sta dst+1
 *    ... (N times, freeing up X/Y completely and eliminating all loop branches)
 *
 * Pattern C (Countdown Absolute Y-indexed):
 *    ldy #N
 *  !:
 *    lda src-1,y
 *    sta dst-1,y
 *    dey
 *    bne !-
 *
 * Unrolled:
 *    lda src
 *    sta dst
 *    lda src+1
 *    sta dst+1
 *    ... (N times)
 */
public class Pass5SmallMemcpyUnrollingOptimization extends Pass5AsmOptimization {

   public Pass5SmallMemcpyUnrollingOptimization(Program program) {
      super(program);
   }

   private static boolean isImmVal(String operand, int val) {
      if(operand == null) return false;
      String t = operand.trim();
      if(t.startsWith("#")) {
         t = t.substring(1).trim();
      }
      try {
         long num;
         if(t.startsWith("$")) {
            num = Long.parseLong(t.substring(1), 16);
         } else if(t.startsWith("0x") || t.startsWith("0X")) {
            num = Long.parseLong(t.substring(2), 16);
         } else if(t.startsWith("%")) {
            num = Long.parseLong(t.substring(1), 2);
         } else {
            num = Long.parseLong(t);
         }
         return num == val;
      } catch(NumberFormatException e) {
         return false;
      }
   }

   private int getImmOrConstVal(String operand) {
      if(operand == null) return -1;
      String t = operand.trim();
      if(t.startsWith("#")) {
         t = t.substring(1).trim();
      }
      // Check numeric
      try {
         if(t.startsWith("$")) {
            return (int) Long.parseLong(t.substring(1), 16);
         } else if(t.startsWith("0x") || t.startsWith("0X")) {
            return (int) Long.parseLong(t.substring(2), 16);
         } else if(t.startsWith("%")) {
            return (int) Long.parseLong(t.substring(1), 2);
         } else {
            return (int) Long.parseLong(t);
         }
      } catch(NumberFormatException e) {
         // Check program scope constant symbol (e.g. SIZEOF_STRUCT_VECTOR)
         Variable constant = getProgram().getScope().getLocalConstant(t);
         if(constant != null && constant.getInitValue() instanceof dk.camelot64.kickc.model.values.ConstantInteger ci) {
            return (int) (long) ci.getValue();
         }
         return -1;
      }
   }

   /**
    * CF-15: Strips a trailing "- 1" (with optional whitespace) from an operand.
    * Returns null if the operand does NOT end with "-1" (distinguishing 0-based src-1,y from 1-based src,y!).
    */
   private static String stripMinusOneExact(String op) {
      if(op == null) return null;
      String t = op.trim();
      java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?)\\s*-\\s*1$").matcher(t);
      if(m.matches()) {
         return m.group(1).trim();
      }
      return null;
   }

   private static String formatOffsetOperand(String base, int offset) {
      if(offset == 0) return base;
      return base + "+" + offset;
   }

   private static void populateInstToGlobalIdx(List<LineRef> allLines, Map<AsmInstruction, Integer> instToGlobalIdx) {
      instToGlobalIdx.clear();
      for(int gi = 0; gi < allLines.size(); gi++) {
         if(allLines.get(gi).line instanceof AsmInstruction ai) {
            instToGlobalIdx.put(ai, gi);
         }
      }
   }

   @Override
   public boolean optimize() {
      boolean modified = false;

      CpuOpcode ldaAbs = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lda", CpuAddressingMode.ABS, false);
      CpuOpcode staAbs = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("sta", CpuAddressingMode.ABS, false);
      CpuOpcode inyOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("iny", CpuAddressingMode.NON, false);

      List<LineRef> allLines = getSharedAllLines();
      Map<String, CanonicalResolver> scopeResolvers = getSharedScopeResolvers(allLines);
      Map<AsmInstruction, Integer> instToGlobalIdx = new IdentityHashMap<>();
      populateInstToGlobalIdx(allLines, instToGlobalIdx);
      boolean linesStale = false;

      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         List<AsmLine> lines = chunk.getLines();

         for(int i = 0; i <= lines.size() - 6; i++) {
            // Pattern C: Countdown loop (6 lines)
            // 0: LDY #N (or LDX #N)
            // 1: Label
            // 2: LDA src[-1],Y
            // 3: STA dst[-1],Y
            // 4: DEY (or DEX)
            // 5: BNE label
            if(lines.get(i) instanceof AsmInstruction inst0 &&
               lines.get(i + 1) instanceof AsmLabel lbl1 &&
               lines.get(i + 2) instanceof AsmInstruction inst2 &&
               lines.get(i + 3) instanceof AsmInstruction inst3 &&
               lines.get(i + 4) instanceof AsmInstruction inst4 &&
               lines.get(i + 5) instanceof AsmInstruction inst5) {

               if(!(inst0.isNoOptimize() || inst2.isNoOptimize() || inst3.isNoOptimize() || inst4.isNoOptimize() || inst5.isNoOptimize())) {
                  String m0 = inst0.getCpuOpcode().getMnemonic().toLowerCase();
                  String m2 = inst2.getCpuOpcode().getMnemonic().toLowerCase();
                  String m3 = inst3.getCpuOpcode().getMnemonic().toLowerCase();
                  String m4 = inst4.getCpuOpcode().getMnemonic().toLowerCase();
                  String m5 = inst5.getCpuOpcode().getMnemonic().toLowerCase();

                  String loopLabel = lbl1.getLabel();
                  String branchTarget = inst5.getOperand1() != null ? inst5.getOperand1().trim() : "";
                  boolean targetsLoop = branchTarget.equals(loopLabel) || branchTarget.equals("!-") || branchTarget.equals("!n-");

                  if(targetsLoop && "bne".equals(m5) && "lda".equals(m2) && "sta".equals(m3)) {
                     boolean isY = "ldy".equals(m0) && "dey".equals(m4) &&
                                   inst2.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.ABY) &&
                                   inst3.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.ABY);
                     boolean isX = "ldx".equals(m0) && "dex".equals(m4) &&
                                   inst2.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.ABX) &&
                                   inst3.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.ABX);

                     if(isY || isX) {
                        int count = getImmOrConstVal(inst0.getOperand1());
                        if(count >= 2 && count <= 4) {
                           if(linesStale) {
                              allLines = flattenLines(getAsmProgram());
                              scopeResolvers = buildScopeResolvers(allLines);
                              populateInstToGlobalIdx(allLines, instToGlobalIdx);
                              linesStale = false;
                           }
                           Integer endGlobalIdx = instToGlobalIdx.get(inst5);
                           if(endGlobalIdx != null) {
                              String scope = allLines.get(endGlobalIdx).scope;
                              if(!isScopeDirty(scope)) continue;
                              CanonicalResolver resolver = scopeResolvers.getOrDefault(scope, scopeResolvers.get(""));
                              if(!isVolatileOrHardwareOperand(inst2, scope, resolver, getProgram()) &&
                                 !isVolatileOrHardwareOperand(inst3, scope, resolver, getProgram())) {
                                 // CF-15: Preserve post-loop X/Y value and flags if live
                                 String idxReg = isY ? "Y" : "X";
                                 boolean idxDead = isRegDeadAlongPath(idxReg, allLines, endGlobalIdx + 1, new HashSet<>(), scope, getProgram());
                                 if(idxDead && (getLiveFlagsAfter(allLines, endGlobalIdx, scope, getProgram()) & FLAG_NZ) == 0) {
                                    String srcStripped = stripMinusOneExact(inst2.getOperand1());
                                    String dstStripped = stripMinusOneExact(inst3.getOperand1());
                                    String srcBase = (srcStripped != null) ? srcStripped : inst2.getOperand1().trim();
                                    String dstBase = (dstStripped != null) ? dstStripped : inst3.getOperand1().trim();
                                    int srcShift = (srcStripped != null) ? -1 : 0;
                                    int dstShift = (dstStripped != null) ? -1 : 0;

                                    // CF-15: Preserve exact descending copy direction (y = count .. 1) for overlapping buffers!
                                    List<AsmLine> unrolled = new ArrayList<>();
                                    for(int y = count; y >= 1; y--) {
                                       String srcOp = formatOffsetOperand(srcBase, y + srcShift);
                                       String dstOp = formatOffsetOperand(dstBase, y + dstShift);
                                       unrolled.add(new AsmInstruction(ldaAbs, srcOp, null, null));
                                       unrolled.add(new AsmInstruction(staAbs, dstOp, null, null));
                                    }

                                    lines.subList(i, i + 6).clear();
                                    lines.addAll(i, unrolled);
                                    markScopeDirty(scope);
                                    invalidateRoundStructure();
                                    modified = true;
                                    linesStale = true;
                                    i += unrolled.size() - 1;
                                    continue;
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }

            // Pattern B & A: Forward loops (7 lines)
            if(i <= lines.size() - 7 &&
               lines.get(i) instanceof AsmInstruction inst0 &&
               lines.get(i + 1) instanceof AsmLabel lbl1 &&
               lines.get(i + 2) instanceof AsmInstruction inst2 &&
               lines.get(i + 3) instanceof AsmInstruction inst3 &&
               lines.get(i + 4) instanceof AsmInstruction inst4 &&
               lines.get(i + 5) instanceof AsmInstruction inst5 &&
               lines.get(i + 6) instanceof AsmInstruction inst6) {

               if(inst0.isNoOptimize() || inst2.isNoOptimize() || inst3.isNoOptimize() ||
                  inst4.isNoOptimize() || inst5.isNoOptimize() || inst6.isNoOptimize()) {
                  continue;
               }
               String m0 = inst0.getCpuOpcode().getMnemonic().toLowerCase();
               String m2 = inst2.getCpuOpcode().getMnemonic().toLowerCase();
               String m3 = inst3.getCpuOpcode().getMnemonic().toLowerCase();
               String m4 = inst4.getCpuOpcode().getMnemonic().toLowerCase();
               String m5 = inst5.getCpuOpcode().getMnemonic().toLowerCase();
               String m6 = inst6.getCpuOpcode().getMnemonic().toLowerCase();

               String loopLabel = lbl1.getLabel();
               String branchTarget = inst6.getOperand1() != null ? inst6.getOperand1().trim() : "";
               boolean targetsLoop = branchTarget.equals(loopLabel) || branchTarget.equals("!-") || branchTarget.equals("!n-");

               if(targetsLoop && "bne".equals(m6)) {
                  if(linesStale) {
                     allLines = flattenLines(getAsmProgram());
                     scopeResolvers = buildScopeResolvers(allLines);
                     populateInstToGlobalIdx(allLines, instToGlobalIdx);
                     linesStale = false;
                  }
                  Integer endGlobalIdx = instToGlobalIdx.get(inst6);
                  if(endGlobalIdx == null) continue;
                  String scope = allLines.get(endGlobalIdx).scope;
                  if(!isScopeDirty(scope)) continue;
                  CanonicalResolver resolver = scopeResolvers.getOrDefault(scope, scopeResolvers.get(""));

                  // Check X-indexed absolute:
                  if("ldx".equals(m0) && isImmVal(inst0.getOperand1(), 0) &&
                     "lda".equals(m2) && inst2.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.ABX) &&
                     "sta".equals(m3) && inst3.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.ABX) &&
                     "inx".equals(m4) && "cpx".equals(m5)) {

                     int count = getImmOrConstVal(inst5.getOperand1());
                     if(count >= 2 && count <= 4 &&
                        !isVolatileOrHardwareOperand(inst2, scope, resolver, getProgram()) &&
                        !isVolatileOrHardwareOperand(inst3, scope, resolver, getProgram()) &&
                        isRegDeadAlongPath("X", allLines, endGlobalIdx + 1, new HashSet<>(), scope, getProgram()) &&
                        (getLiveFlagsAfter(allLines, endGlobalIdx, scope, getProgram()) & FLAG_NZC) == 0) {
                        String src = inst2.getOperand1();
                        String dst = inst3.getOperand1();

                        List<AsmLine> unrolled = new ArrayList<>();
                        for(int b = 0; b < count; b++) {
                           String srcOp = formatOffsetOperand(src, b);
                           String dstOp = formatOffsetOperand(dst, b);
                           unrolled.add(new AsmInstruction(ldaAbs, srcOp, null, null));
                           unrolled.add(new AsmInstruction(staAbs, dstOp, null, null));
                        }

                        lines.subList(i, i + 7).clear();
                        lines.addAll(i, unrolled);
                        markScopeDirty(scope);
                        invalidateRoundStructure();
                        modified = true;
                        linesStale = true;
                        i += unrolled.size() - 1;
                        continue;
                     }
                  }
                  // Check Y-indexed absolute:
                  else if("ldy".equals(m0) && isImmVal(inst0.getOperand1(), 0) &&
                          "lda".equals(m2) && inst2.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.ABY) &&
                          "sta".equals(m3) && inst3.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.ABY) &&
                          "iny".equals(m4) && "cpy".equals(m5)) {

                     int count = getImmOrConstVal(inst5.getOperand1());
                     if(count >= 2 && count <= 4 &&
                        !isVolatileOrHardwareOperand(inst2, scope, resolver, getProgram()) &&
                        !isVolatileOrHardwareOperand(inst3, scope, resolver, getProgram()) &&
                        isRegDeadAlongPath("Y", allLines, endGlobalIdx + 1, new HashSet<>(), scope, getProgram()) &&
                        (getLiveFlagsAfter(allLines, endGlobalIdx, scope, getProgram()) & FLAG_NZC) == 0) {
                        String src = inst2.getOperand1();
                        String dst = inst3.getOperand1();

                        List<AsmLine> unrolled = new ArrayList<>();
                        for(int b = 0; b < count; b++) {
                           String srcOp = formatOffsetOperand(src, b);
                           String dstOp = formatOffsetOperand(dst, b);
                           unrolled.add(new AsmInstruction(ldaAbs, srcOp, null, null));
                           unrolled.add(new AsmInstruction(staAbs, dstOp, null, null));
                        }

                        lines.subList(i, i + 7).clear();
                        lines.addAll(i, unrolled);
                        markScopeDirty(scope);
                        invalidateRoundStructure();
                        modified = true;
                        linesStale = true;
                        i += unrolled.size() - 1;
                        continue;
                     }
                  }
                  // Check Pattern A: Indirect Y-indexed:
                  else if("ldy".equals(m0) && isImmVal(inst0.getOperand1(), 0) &&
                          "lda".equals(m2) && inst2.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.IZY) &&
                          "sta".equals(m3) && inst3.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.IZY) &&
                          "iny".equals(m4) && "cpy".equals(m5)) {

                     int count = getImmOrConstVal(inst5.getOperand1());
                     if(count >= 2 && count <= 4 &&
                        !isVolatileOrHardwareOperand(inst2, scope, resolver, getProgram()) &&
                        !isVolatileOrHardwareOperand(inst3, scope, resolver, getProgram()) &&
                        (getLiveFlagsAfter(allLines, endGlobalIdx, scope, getProgram()) & FLAG_NZC) == 0) {
                        boolean yDead = isRegDeadAlongPath("Y", allLines, endGlobalIdx + 1, new HashSet<>(), scope, getProgram());
                        List<AsmLine> unrolled = new ArrayList<>();
                        unrolled.add(inst0); // ldy #0
                        for(int b = 0; b < count; b++) {
                           if(b > 0) {
                              unrolled.add(new AsmInstruction(inyOp, null, null, null));
                           }
                           unrolled.add(new AsmInstruction(inst2.getCpuOpcode(), inst2.getOperand1(), null, null));
                           unrolled.add(new AsmInstruction(inst3.getCpuOpcode(), inst3.getOperand1(), null, null));
                        }
                        if(!yDead) {
                           // Restore post-loop Y == count if Y is live after the loop
                           unrolled.add(new AsmInstruction(inyOp, null, null, null));
                        }

                        lines.subList(i, i + 7).clear();
                        lines.addAll(i, unrolled);
                        markScopeDirty(scope);
                        invalidateRoundStructure();
                        modified = true;
                        linesStale = true;
                        i += unrolled.size() - 1;
                        continue;
                     }
                  }
               }
            }
         }
      }

      return modified;
   }
}
