package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.types.SymbolType;

import java.util.*;

/**
 * Assembler Peephole Optimization: Auto Carry-Flag Boolean Returns (__carry / Auto-Carry ABI).
 *
 * In 6502 assembly, condition checks return results in the Carry flag (SEC for true, CLC for false),
 * and callers branch immediately via BCS or BCC.
 *
 * This pass:
 * 1. Identifies procedures declared with __carry or returning bool where all call-site return values
 *    are strictly used in conditional branches (if, while, etc.).
 * 2. In the callee:
 *    - Replaces 'LDA #1; RTS' with 'SEC; RTS' (1 byte, 2 cycles).
 *    - Replaces 'LDA #0; RTS' with 'CLC; RTS' (1 byte, 2 cycles).
 *    - Collapses redundant comparison branch patterns:
 *        CMP ... ; BCC false ; SEC ; RTS ; false: CLC ; RTS  ->  CMP ... ; RTS
 *        CMP ... ; BCS true  ; CLC ; RTS ; true:  SEC ; RTS  ->  CMP ... ; RTS
 * 3. In the caller:
 *    - Eliminates redundant 'CMP #0' after 'JSR func'.
 *    - Replaces 'BNE target' with 'BCS target'.
 *    - Replaces 'BEQ target' with 'BCC target'.
 */
public class Pass5CarryFlagReturnOptimization extends Pass5AsmOptimization {

   private static class LineRef {
      final int index;
      final AsmChunk chunk;
      final AsmLine line;
      final String scope;

      LineRef(int index, AsmChunk chunk, AsmLine line, String scope) {
         this.index = index;
         this.chunk = chunk;
         this.line = line;
         this.scope = scope;
      }
   }

   public Pass5CarryFlagReturnOptimization(Program program) {
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

   private static boolean isZeroCmp(AsmInstruction inst) {
      if(inst == null || inst.getCpuOpcode() == null) return false;
      String mnem = inst.getCpuOpcode().getMnemonic();
      if(!mnem.equals("cmp")) return false;
      if(!inst.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.IMM)) return false;
      return isImmVal(inst.getOperand1(), 0);
   }

   @Override
   public boolean optimize() {
      // Build allLines initially for analysis
      List<LineRef> initialLines = buildAllLines();
      Set<String> carryProcs = findCarryReturnProcedures(initialLines);
      if(carryProcs.isEmpty()) {
         return false;
      }

      boolean overallModified = false;
      boolean passModified = true;

      CpuOpcode secOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("sec", CpuAddressingMode.NON, false);
      CpuOpcode clcOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("clc", CpuAddressingMode.NON, false);
      CpuOpcode bcsOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("bcs", CpuAddressingMode.REL, false);
      CpuOpcode bccOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("bcc", CpuAddressingMode.REL, false);

      while(passModified) {
         passModified = false;

         // Linearize all lines across chunks
         List<LineRef> allLines = new ArrayList<>();
         String currentScope = null;
         int lineIdx = 0;
         Map<String, Integer> labelIndices = new HashMap<>();

         for(AsmChunk chunk : getAsmProgram().getChunks()) {
            String scope = chunk.getScopeLabel();
            for(AsmLine line : chunk.getLines()) {
               if(line instanceof AsmLabel) {
                  String lbl = ((AsmLabel) line).getLabel();
                  if(!lbl.startsWith("!")) {
                     String key = (scope != null ? scope + "::" : "") + lbl;
                     labelIndices.put(key, lineIdx);
                     labelIndices.put(lbl, lineIdx);
                  }
               }
               allLines.add(new LineRef(lineIdx++, chunk, line, scope));
            }
         }

         // 1. Optimize Callee return points for each carry procedure
         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref = allLines.get(i);
            if(ref.scope != null && carryProcs.contains(ref.scope)) {
               if(ref.line instanceof AsmInstruction inst && "rts".equalsIgnoreCase(inst.getCpuOpcode().getMnemonic())) {
                  // Look backwards for LDA #1 or LDA #0
                  int prevIdx = i - 1;
                  while(prevIdx >= 0 && !(allLines.get(prevIdx).line instanceof AsmInstruction)) {
                     prevIdx--;
                  }
                  if(prevIdx >= 0 && allLines.get(prevIdx).line instanceof AsmInstruction prevInst) {
                     String mn = prevInst.getCpuOpcode().getMnemonic().toLowerCase();
                     if("lda".equals(mn) && prevInst.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.IMM)) {
                        if(isImmVal(prevInst.getOperand1(), 1)) {
                           // Replace with SEC
                           prevInst.setCpuOpcode(secOp);
                           prevInst.setOperand1(null);
                           prevInst.setOperand2(null);
                           prevInst.setOperand3(null);
                           passModified = true;
                           overallModified = true;
                           break;
                        } else if(isImmVal(prevInst.getOperand1(), 0)) {
                           // Replace with CLC
                           prevInst.setCpuOpcode(clcOp);
                           prevInst.setOperand1(null);
                           prevInst.setOperand2(null);
                           prevInst.setOperand3(null);
                           passModified = true;
                           overallModified = true;
                           break;
                        }
                     } else if("rol".equals(mn)) {
                        int prevLdaIdx = prevIdx - 1;
                        while(prevLdaIdx >= 0 && !(allLines.get(prevLdaIdx).line instanceof AsmInstruction)) {
                           prevLdaIdx--;
                        }
                        if(prevLdaIdx >= 0 && allLines.get(prevLdaIdx).line instanceof AsmInstruction ldaInst) {
                           if("lda".equalsIgnoreCase(ldaInst.getCpuOpcode().getMnemonic()) && isImmVal(ldaInst.getOperand1(), 0)) {
                              int prevCmpIdx = prevLdaIdx - 1;
                              while(prevCmpIdx >= 0 && !(allLines.get(prevCmpIdx).line instanceof AsmInstruction)) {
                                 prevCmpIdx--;
                              }
                              if(prevCmpIdx >= 0 && allLines.get(prevCmpIdx).line instanceof AsmInstruction cmpInst) {
                                 String cmn = cmpInst.getCpuOpcode().getMnemonic().toLowerCase();
                                 if("cmp".equals(cmn) || "cpx".equals(cmn) || "cpy".equals(cmn)) {
                                    allLines.get(prevLdaIdx).chunk.getLines().remove(ldaInst);
                                    allLines.get(prevIdx).chunk.getLines().remove(prevInst);
                                    passModified = true;
                                    overallModified = true;
                                    break;
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }

         if(passModified) continue;

         // 1b. Callee redundant comparison branch collapse:
         // CMP ... ; BCC lbl_false ; SEC ; RTS ... lbl_false: CLC ; RTS
         for(int i = 0; i < allLines.size() - 4; i++) {
            LineRef refCmp = allLines.get(i);
            if(refCmp.scope != null && carryProcs.contains(refCmp.scope) && refCmp.line instanceof AsmInstruction instCmp && "cmp".equalsIgnoreCase(instCmp.getCpuOpcode().getMnemonic())) {
               int idxB = nextInstIdx(allLines, i + 1);
               if(idxB == -1) continue;
               LineRef refB = allLines.get(idxB);
               if(refB.line instanceof AsmInstruction instB) {
                  String bmn = instB.getCpuOpcode().getMnemonic().toLowerCase();
                  String bTarget = instB.getOperand1();

                  int idxSec = nextInstIdx(allLines, idxB + 1);
                  if(idxSec == -1) continue;
                  LineRef refSec = allLines.get(idxSec);

                  int idxRts = nextInstIdx(allLines, idxSec + 1);
                  if(idxRts == -1) continue;
                  LineRef refRts = allLines.get(idxRts);

                  if(isRts(refRts.line)) {
                     if("bcc".equals(bmn) && isSec(refSec.line)) {
                        // Check if bTarget leads to CLC; RTS
                        Integer targetIdx = labelIndices.get(bTarget);
                        if(targetIdx != null) {
                           int idxClc = nextInstIdx(allLines, targetIdx + 1);
                           if(idxClc != -1 && isClc(allLines.get(idxClc).line)) {
                              int idxRts2 = nextInstIdx(allLines, idxClc + 1);
                              if(idxRts2 != -1 && isRts(allLines.get(idxRts2).line)) {
                                 // CMP already sets Carry: eliminate BCC and SEC!
                                 refB.chunk.getLines().remove(instB);
                                 refSec.chunk.getLines().remove(refSec.line);
                                 passModified = true;
                                 overallModified = true;
                                 break;
                              }
                           }
                        }
                     } else if("bcs".equals(bmn) && isClc(refSec.line)) {
                        Integer targetIdx = labelIndices.get(bTarget);
                        if(targetIdx != null) {
                           int idxSec2 = nextInstIdx(allLines, targetIdx + 1);
                           if(idxSec2 != -1 && isSec(allLines.get(idxSec2).line)) {
                              int idxRts2 = nextInstIdx(allLines, idxSec2 + 1);
                              if(idxRts2 != -1 && isRts(allLines.get(idxRts2).line)) {
                                 refB.chunk.getLines().remove(instB);
                                 refSec.chunk.getLines().remove(refSec.line);
                                 passModified = true;
                                 overallModified = true;
                                 break;
                              }
                           }
                        }
                     }
                  }
               }
            }
         }

         if(passModified) continue;

         // 2. Caller call sites optimization:
         // JSR carry_proc
         // CMP #0 (eliminated)
         // BNE -> BCS, BEQ -> BCC
         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref = allLines.get(i);
            if(ref.line instanceof AsmInstruction inst && "jsr".equalsIgnoreCase(inst.getCpuOpcode().getMnemonic())) {
               String target = inst.getOperand1();
               if(target != null && carryProcs.contains(target.trim())) {
                  int nextIdx = nextInstIdx(allLines, i + 1);
                  if(nextIdx != -1) {
                     LineRef refNext = allLines.get(nextIdx);
                     if(refNext.line instanceof AsmInstruction nextInst) {
                        String mn = nextInst.getCpuOpcode().getMnemonic().toLowerCase();
                        if(isZeroCmp(nextInst)) {
                           int branchIdx = nextInstIdx(allLines, nextIdx + 1);
                           if(branchIdx != -1) {
                              LineRef refBranch = allLines.get(branchIdx);
                              if(refBranch.line instanceof AsmInstruction branchInst) {
                                 String bmn = branchInst.getCpuOpcode().getMnemonic().toLowerCase();
                                 if("bne".equals(bmn)) {
                                    branchInst.setCpuOpcode(bcsOp);
                                    refNext.chunk.getLines().remove(nextInst);
                                    passModified = true;
                                    overallModified = true;
                                    break;
                                 } else if("beq".equals(bmn)) {
                                    branchInst.setCpuOpcode(bccOp);
                                    refNext.chunk.getLines().remove(nextInst);
                                    passModified = true;
                                    overallModified = true;
                                    break;
                                 }
                              }
                           }
                        } else if("bne".equals(mn)) {
                           nextInst.setCpuOpcode(bcsOp);
                           passModified = true;
                           overallModified = true;
                           break;
                        } else if("beq".equals(mn)) {
                           nextInst.setCpuOpcode(bccOp);
                           passModified = true;
                           overallModified = true;
                           break;
                        } else if(isRts(nextInst) && (ref.scope == null || !carryProcs.contains(ref.scope))) {
                           // Caller is NOT a carry procedure and immediately returns the result:
                           // Normalize Carry to Register A: LDA #0 ; ROL
                           CpuOpcode ldaImm = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lda", CpuAddressingMode.IMM, false);
                           CpuOpcode rolOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("rol", CpuAddressingMode.NON, false);
                           refNext.chunk.addLineBefore(nextInst, new AsmInstruction(ldaImm, "0", null, null));
                           refNext.chunk.addLineBefore(nextInst, new AsmInstruction(rolOp, null, null, null));
                           passModified = true;
                           overallModified = true;
                           break;
                        } else if(mn.startsWith("sta") && (ref.scope == null || !carryProcs.contains(ref.scope))) {
                           // Call site stores the boolean result: normalize Carry to Register A: LDA #0 ; ROL
                           CpuOpcode ldaImm = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lda", CpuAddressingMode.IMM, false);
                           CpuOpcode rolOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("rol", CpuAddressingMode.NON, false);
                           refNext.chunk.addLineBefore(nextInst, new AsmInstruction(ldaImm, "0", null, null));
                           refNext.chunk.addLineBefore(nextInst, new AsmInstruction(rolOp, null, null, null));
                           passModified = true;
                           overallModified = true;
                           break;
                        }
                     }
                  }
               }
            }
         }
      }

      return overallModified;
   }

   private int nextInstIdx(List<LineRef> allLines, int startIdx) {
      for(int i = startIdx; i < allLines.size(); i++) {
         AsmLine line = allLines.get(i).line;
         if(line instanceof AsmInstruction) {
            return i;
         }
      }
      return -1;
   }

   private boolean isSec(AsmLine line) {
      return line instanceof AsmInstruction inst && "sec".equalsIgnoreCase(inst.getCpuOpcode().getMnemonic());
   }

   private boolean isClc(AsmLine line) {
      return line instanceof AsmInstruction inst && "clc".equalsIgnoreCase(inst.getCpuOpcode().getMnemonic());
   }

   private boolean isRts(AsmLine line) {
      return line instanceof AsmInstruction inst && "rts".equalsIgnoreCase(inst.getCpuOpcode().getMnemonic());
   }

   /**
    * Finds all procedures that qualify for Carry return:
    * - Declared with __carry directive.
    * - OR returning bool where every call site in the program only tests the return value via branch.
    */
   private Set<String> findCarryReturnProcedures(List<LineRef> allLines) {
      Set<String> carryProcs = new HashSet<>();
      Scope rootScope = getProgram().getScope();

      for(Procedure procedure : rootScope.getAllProcedures(true)) {
         String name = procedure.getLocalName();
         if(procedure.isCarryReturn()) {
            carryProcs.add(name);
            continue;
         }

         if(SymbolType.BOOLEAN.equals(procedure.getReturnType()) && procedure.getInterruptType() == null) {
            if(calleeCanReturnCarry(allLines, name) && allCallSitesAreBranches(allLines, name)) {
               carryProcs.add(name);
               procedure.setCarryReturn(true);
            }
         }
      }
      return carryProcs;
   }

   private List<LineRef> buildAllLines() {
      List<LineRef> allLines = new ArrayList<>();
      int lineIdx = 0;
      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         String scope = chunk.getScopeLabel();
         for(AsmLine line : chunk.getLines()) {
            allLines.add(new LineRef(lineIdx++, chunk, line, scope));
         }
      }
      return allLines;
   }

   private boolean calleeCanReturnCarry(List<LineRef> allLines, String procName) {
      int rtsCount = 0;
      for(int i = 0; i < allLines.size(); i++) {
         LineRef ref = allLines.get(i);
         if(procName.equals(ref.scope)) {
            if(ref.line instanceof AsmInstruction inst && "rts".equalsIgnoreCase(inst.getCpuOpcode().getMnemonic())) {
               rtsCount++;
               int prevIdx = i - 1;
               boolean hasLabel = false;
               while(prevIdx >= 0 && !(allLines.get(prevIdx).line instanceof AsmInstruction)) {
                  if(allLines.get(prevIdx).line instanceof AsmLabel) {
                     hasLabel = true;
                  }
                  prevIdx--;
               }
               if(hasLabel || prevIdx < 0 || !(allLines.get(prevIdx).line instanceof AsmInstruction)) {
                  return false;
               }
               AsmInstruction prevInst = (AsmInstruction) allLines.get(prevIdx).line;
               String mn = prevInst.getCpuOpcode().getMnemonic().toLowerCase();

               // Already returns in carry
               if("sec".equals(mn) || "clc".equals(mn) || "cmp".equals(mn) || "cpx".equals(mn) || "cpy".equals(mn)) {
                  continue;
               }

               // Transformable lda #0 or lda #1
               if("lda".equals(mn) && prevInst.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.IMM)) {
                  if(isImmVal(prevInst.getOperand1(), 0) || isImmVal(prevInst.getOperand1(), 1)) {
                     continue;
                  }
               }

               // Transformable cmp; lda #0; rol
               if("rol".equals(mn)) {
                  int prevLdaIdx = prevIdx - 1;
                  while(prevLdaIdx >= 0 && !(allLines.get(prevLdaIdx).line instanceof AsmInstruction)) {
                     prevLdaIdx--;
                  }
                  if(prevLdaIdx >= 0 && allLines.get(prevLdaIdx).line instanceof AsmInstruction ldaInst) {
                     if("lda".equalsIgnoreCase(ldaInst.getCpuOpcode().getMnemonic()) && isImmVal(ldaInst.getOperand1(), 0)) {
                        int prevCmpIdx = prevLdaIdx - 1;
                        while(prevCmpIdx >= 0 && !(allLines.get(prevCmpIdx).line instanceof AsmInstruction)) {
                           prevCmpIdx--;
                        }
                        if(prevCmpIdx >= 0 && allLines.get(prevCmpIdx).line instanceof AsmInstruction cmpInst) {
                           String cmn = cmpInst.getCpuOpcode().getMnemonic().toLowerCase();
                           if("cmp".equals(cmn) || "cpx".equals(cmn) || "cpy".equals(cmn)) {
                              continue;
                           }
                        }
                     }
                  }
               }

               // Any other instruction (e.g. lda reu_available, ora, eor, etc.) cannot safely return carry
               return false;
            }
         }
      }
      return rtsCount > 0;
   }

   private boolean allCallSitesAreBranches(List<LineRef> allLines, String procName) {
      int callCount = 0;
      for(int i = 0; i < allLines.size(); i++) {
         AsmLine line = allLines.get(i).line;
         if(line instanceof AsmInstruction inst && "jsr".equalsIgnoreCase(inst.getCpuOpcode().getMnemonic())) {
            String target = inst.getOperand1();
            if(target != null && target.trim().equals(procName)) {
               callCount++;
               int nextIdx = nextInstIdx(allLines, i + 1);
               if(nextIdx == -1) return false;

               AsmInstruction nextInst = (AsmInstruction) allLines.get(nextIdx).line;
               String mn = nextInst.getCpuOpcode().getMnemonic().toLowerCase();

               if(mn.equals("bne") || mn.equals("beq") || mn.equals("bcs") || mn.equals("bcc")) {
                  continue;
               }
               if(isZeroCmp(nextInst)) {
                  int branchIdx = nextInstIdx(allLines, nextIdx + 1);
                  if(branchIdx != -1 && allLines.get(branchIdx).line instanceof AsmInstruction branchInst) {
                     String bmn = branchInst.getCpuOpcode().getMnemonic().toLowerCase();
                     if(bmn.equals("bne") || bmn.equals("beq")) {
                        continue;
                     }
                  }
               }
               return false;
            }
         }
      }
      return callCount > 0;
   }
}
