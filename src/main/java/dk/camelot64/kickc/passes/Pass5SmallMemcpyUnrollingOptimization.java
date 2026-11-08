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

   private static String stripMinusOne(String op) {
      if(op == null) return null;
      String t = op.trim();
      if(t.endsWith("-1")) {
         return t.substring(0, t.length() - 2).trim();
      }
      return t;
   }

   @Override
   public boolean optimize() {
      boolean modified = false;

      CpuOpcode ldaAbs = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lda", CpuAddressingMode.ABS, false);
      CpuOpcode staAbs = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("sta", CpuAddressingMode.ABS, false);
      CpuOpcode inyOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("iny", CpuAddressingMode.NON, false);

      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         List<AsmLine> lines = chunk.getLines();
         boolean chunkModified = true;

         while(chunkModified) {
            chunkModified = false;

            // Pattern C: Countdown loop (6 lines)
            // 0: LDY #N (or LDX #N)
            // 1: Label
            // 2: LDA src-1,Y
            // 3: STA dst-1,Y
            // 4: DEY (or DEX)
            // 5: BNE label
            for(int i = 0; i <= lines.size() - 6; i++) {
               if(lines.get(i) instanceof AsmInstruction inst0 &&
                  lines.get(i + 1) instanceof AsmLabel lbl1 &&
                  lines.get(i + 2) instanceof AsmInstruction inst2 &&
                  lines.get(i + 3) instanceof AsmInstruction inst3 &&
                  lines.get(i + 4) instanceof AsmInstruction inst4 &&
                  lines.get(i + 5) instanceof AsmInstruction inst5) {

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
                           String src = stripMinusOne(inst2.getOperand1());
                           String dst = stripMinusOne(inst3.getOperand1());

                           List<AsmLine> unrolled = new ArrayList<>();
                           for(int b = 0; b < count; b++) {
                              String srcOp = (b == 0) ? src : (src + "+" + b);
                              String dstOp = (b == 0) ? dst : (dst + "+" + b);
                              unrolled.add(new AsmInstruction(ldaAbs, srcOp, null, null));
                              unrolled.add(new AsmInstruction(staAbs, dstOp, null, null));
                           }

                           for(int k = 0; k < 6; k++) {
                              lines.remove(i);
                           }
                           lines.addAll(i, unrolled);
                           chunkModified = true;
                           modified = true;
                           break;
                        }
                     }
                  }
               }
            }

            if(chunkModified) continue;

            // Pattern B & A: Forward loops (7 lines)
            for(int i = 0; i <= lines.size() - 7; i++) {
               if(lines.get(i) instanceof AsmInstruction inst0 &&
                  lines.get(i + 1) instanceof AsmLabel lbl1 &&
                  lines.get(i + 2) instanceof AsmInstruction inst2 &&
                  lines.get(i + 3) instanceof AsmInstruction inst3 &&
                  lines.get(i + 4) instanceof AsmInstruction inst4 &&
                  lines.get(i + 5) instanceof AsmInstruction inst5 &&
                  lines.get(i + 6) instanceof AsmInstruction inst6) {

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
                     // Check X-indexed absolute:
                     if("ldx".equals(m0) && isImmVal(inst0.getOperand1(), 0) &&
                        "lda".equals(m2) && inst2.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.ABX) &&
                        "sta".equals(m3) && inst3.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.ABX) &&
                        "inx".equals(m4) && "cpx".equals(m5)) {

                        int count = getImmOrConstVal(inst5.getOperand1());
                        if(count >= 2 && count <= 4) {
                           String src = inst2.getOperand1();
                           String dst = inst3.getOperand1();

                           List<AsmLine> unrolled = new ArrayList<>();
                           for(int b = 0; b < count; b++) {
                              String srcOp = (b == 0) ? src : (src + "+" + b);
                              String dstOp = (b == 0) ? dst : (dst + "+" + b);
                              unrolled.add(new AsmInstruction(ldaAbs, srcOp, null, null));
                              unrolled.add(new AsmInstruction(staAbs, dstOp, null, null));
                           }

                           for(int k = 0; k < 7; k++) {
                              lines.remove(i);
                           }
                           lines.addAll(i, unrolled);
                           chunkModified = true;
                           modified = true;
                           break;
                        }
                     }
                     // Check Y-indexed absolute:
                     else if("ldy".equals(m0) && isImmVal(inst0.getOperand1(), 0) &&
                             "lda".equals(m2) && inst2.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.ABY) &&
                             "sta".equals(m3) && inst3.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.ABY) &&
                             "iny".equals(m4) && "cpy".equals(m5)) {

                        int count = getImmOrConstVal(inst5.getOperand1());
                        if(count >= 2 && count <= 4) {
                           String src = inst2.getOperand1();
                           String dst = inst3.getOperand1();

                           List<AsmLine> unrolled = new ArrayList<>();
                           for(int b = 0; b < count; b++) {
                              String srcOp = (b == 0) ? src : (src + "+" + b);
                              String dstOp = (b == 0) ? dst : (dst + "+" + b);
                              unrolled.add(new AsmInstruction(ldaAbs, srcOp, null, null));
                              unrolled.add(new AsmInstruction(staAbs, dstOp, null, null));
                           }

                           for(int k = 0; k < 7; k++) {
                              lines.remove(i);
                           }
                           lines.addAll(i, unrolled);
                           chunkModified = true;
                           modified = true;
                           break;
                        }
                     }
                     // Check Pattern A: Indirect Y-indexed:
                     else if("ldy".equals(m0) && isImmVal(inst0.getOperand1(), 0) &&
                             "lda".equals(m2) && inst2.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.IZY) &&
                             "sta".equals(m3) && inst3.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.IZY) &&
                             "iny".equals(m4) && "cpy".equals(m5)) {

                        int count = getImmOrConstVal(inst5.getOperand1());
                        if(count >= 2 && count <= 4) {
                           List<AsmLine> unrolled = new ArrayList<>();
                           unrolled.add(inst0); // ldy #0
                           for(int b = 0; b < count; b++) {
                              if(b > 0) {
                                 unrolled.add(new AsmInstruction(inyOp, null, null, null));
                              }
                              unrolled.add(new AsmInstruction(inst2.getCpuOpcode(), inst2.getOperand1(), null, null));
                              unrolled.add(new AsmInstruction(inst3.getCpuOpcode(), inst3.getOperand1(), null, null));
                           }

                           for(int k = 0; k < 7; k++) {
                              lines.remove(i);
                           }
                           lines.addAll(i, unrolled);
                           chunkModified = true;
                           modified = true;
                           break;
                        }
                     }
                  }
               }
            }
         }
      }

      return modified;
   }
}
