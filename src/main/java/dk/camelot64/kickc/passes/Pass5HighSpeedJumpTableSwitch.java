package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.Cpu65xx;
import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Symbol;
import dk.camelot64.kickc.model.types.SymbolType;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pass 5 Optimization: High-Speed Jump-Table switch (RTS-Trick).
 * Replaces dense linear comparison chains (CMP #val; BEQ target) across chunks
 * with an O(1) jump table.
 *
 * Dispatch sequence:
 *   [sec; sbc #min]   (if min > 0)
 *   cmp #span
 *   bcs defaultLabel
 *   tax
 *   lda jmp_table_hi,x
 *   pha
 *   lda jmp_table_lo,x
 *   pha
 *   rts
 *
 * Runs in ~21-26 CPU cycles flat regardless of case count.
 * Formally verifies that neither register A nor register X is live into any of the target cases.
 */
public class Pass5HighSpeedJumpTableSwitch extends Pass5AsmOptimization {

   private static final AtomicInteger tableCounter = new AtomicInteger(0);

   public Pass5HighSpeedJumpTableSwitch(Program program) {
      super(program);
   }

   private static class CaseBranch {
      final int value;
      final String targetLabel;
      final List<LineRef> linesToRemove;

      CaseBranch(int value, String targetLabel, List<LineRef> linesToRemove) {
         this.value = value;
         this.targetLabel = targetLabel;
         this.linesToRemove = linesToRemove;
      }
   }

   @Override
   public boolean optimize() {
      boolean modified = false;

      while(true) {
         // Flatten all lines across chunks
         Map<String, Integer> labelLineMap = new HashMap<>();
         List<LineRef> allLines = new ArrayList<>();
         String currentScope = null;
         int lineIdx = 0;

         for(AsmChunk chunk : getAsmProgram().getChunks()) {
            for(AsmLine line : chunk.getLines()) {
               if(line instanceof AsmScopeBegin) {
                  currentScope = ((AsmScopeBegin) line).getLabel();
               } else if(line instanceof AsmScopeEnd) {
                  currentScope = null;
               } else if(line instanceof AsmLabel) {
                  String lbl = ((AsmLabel) line).getLabel();
                  if(lbl != null && !lbl.startsWith("!")) {
                     String key = (currentScope != null ? currentScope : "") + "::" + lbl;
                     labelLineMap.put(key, lineIdx);
                  }
               }
               allLines.add(new LineRef(lineIdx++, chunk, line, currentScope));
            }
         }

         boolean roundModified = false;

         // Scan allLines for contiguous chains of CMP #imm; BEQ target
         for(int i = 0; i < allLines.size(); i++) {
            List<CaseBranch> chain = matchCaseChain(allLines, i);
            if(chain != null && chain.size() >= 4) {
               String chainScope = allLines.get(i).scope != null ? allLines.get(i).scope : "";
               // Calculate min, max, span, density
               int minVal = Integer.MAX_VALUE;
               int maxVal = Integer.MIN_VALUE;
               Set<Integer> uniqueVals = new HashSet<>();
               for(CaseBranch cb : chain) {
                  minVal = Math.min(minVal, cb.value);
                  maxVal = Math.max(maxVal, cb.value);
                  uniqueVals.add(cb.value);
               }

               int span = maxVal - minVal + 1;
               double density = (double) uniqueVals.size() / span;

               // Apply optimization only if range <= 32, density >= 0.5, 0 <= minVal <= maxVal <= 255
               Set<String> uniqueTargets = new HashSet<>();
               for(CaseBranch cb : chain) {
                  uniqueTargets.add(cb.targetLabel);
               }

               // Apply optimization only if multi-way switch (>=3 distinct targets), range <= 32, density >= 0.5, 0 <= minVal <= maxVal <= 255
               if(uniqueTargets.size() >= 3 && span <= 32 && density >= 0.5 && minVal >= 0 && maxVal <= 255) {
                  // Determine default target
                  CaseBranch lastCase = chain.get(chain.size() - 1);
                  LineRef lastRef = lastCase.linesToRemove.get(lastCase.linesToRemove.size() - 1);
                  int endChainIdx = lastRef.index;

                  String defaultTarget = null;
                  boolean defaultIsFallthrough = false;
                  LineRef jmpDefRef = null;

                  int nextInstIdx = findNextInstruction(allLines, endChainIdx + 1);
                  if(nextInstIdx != -1) {
                     AsmLine nextLine = allLines.get(nextInstIdx).line;
                     if(nextLine instanceof AsmInstruction) {
                        AsmInstruction nextInst = (AsmInstruction) nextLine;
                        if(nextInst.getCpuOpcode() != null && "jmp".equals(nextInst.getCpuOpcode().getMnemonic())
                              && CpuAddressingMode.ABS.equals(nextInst.getCpuOpcode().getAddressingMode())) {
                           defaultTarget = nextInst.getOperand1();
                           jmpDefRef = allLines.get(nextInstIdx);
                        }
                     }
                  }

                  if(defaultTarget == null) {
                     defaultIsFallthrough = true;
                  }

                  // Verify register safety for A and X in all targets strictly within chainScope
                  boolean safe = true;
                  for(CaseBranch cb : chain) {
                     if(!isRegisterSafeForTarget(allLines, labelLineMap, chainScope, cb.targetLabel, "A") ||
                        !isRegisterSafeForTarget(allLines, labelLineMap, chainScope, cb.targetLabel, "X")) {
                        safe = false;
                        break;
                     }
                  }

                  if(safe && defaultTarget != null && !defaultIsFallthrough) {
                     if(!isRegisterSafeForTarget(allLines, labelLineMap, chainScope, defaultTarget, "A") ||
                        !isRegisterSafeForTarget(allLines, labelLineMap, chainScope, defaultTarget, "X")) {
                        safe = false;
                     }
                  } else if(safe && defaultIsFallthrough) {
                     // On fallthrough, X is untouched before BCS, but A is modified if minVal > 0
                     if(minVal > 0 && !isRegisterSafeFromIndex(allLines, labelLineMap, chainScope, endChainIdx + 1, "A", new HashSet<>(), 0)) {
                        safe = false;
                     }
                  }

                  if(safe) {
                     Cpu65xx cpu = getAsmProgram().getTargetCpu().getCpu65xx();
                     CpuOpcode secOp = cpu.getOpcode("sec", CpuAddressingMode.NON, false);
                     CpuOpcode sbcImm = cpu.getOpcode("sbc", CpuAddressingMode.IMM, false);
                     CpuOpcode cmpImm = cpu.getOpcode("cmp", CpuAddressingMode.IMM, false);
                     CpuOpcode bcsRel = cpu.getOpcode("bcs", CpuAddressingMode.REL, false);
                     CpuOpcode taxOp = cpu.getOpcode("tax", CpuAddressingMode.NON, false);
                     CpuOpcode ldaAbx = cpu.getOpcode("lda", CpuAddressingMode.ABX, false);
                     CpuOpcode phaOp = cpu.getOpcode("pha", CpuAddressingMode.NON, false);
                     CpuOpcode rtsOp = cpu.getOpcode("rts", CpuAddressingMode.NON, false);
                     if(cmpImm == null || bcsRel == null || taxOp == null || ldaAbx == null || phaOp == null || rtsOp == null) {
                        continue;
                     }
                     if(minVal > 0 && (secOp == null || sbcImm == null)) {
                        continue;
                     }

                     // Transform chain into Jump Table!
                     int tableId = tableCounter.incrementAndGet();
                     String tableLoName = "__switch_lo_" + tableId;
                     String tableHiName = "__switch_hi_" + tableId;

                     String defLabelForTable = defaultIsFallthrough ? ("__sw_def_" + tableId) : defaultTarget;

                     // Build table strings
                     StringBuilder loBuilder = new StringBuilder();
                     StringBuilder hiBuilder = new StringBuilder();
                     Map<Integer, String> valueToTarget = new HashMap<>();
                     for(CaseBranch cb : chain) {
                        valueToTarget.put(cb.value, cb.targetLabel);
                     }

                     for(int v = 0; v < span; v++) {
                        int curVal = minVal + v;
                        String tgt = valueToTarget.getOrDefault(curVal, defLabelForTable);
                        if(v > 0) {
                           loBuilder.append(", ");
                           hiBuilder.append(", ");
                        }
                        loBuilder.append("<(").append(tgt).append("-1)");
                        hiBuilder.append(">(").append(tgt).append("-1)");
                     }

                     // Emit real AsmInstruction objects so Pass5FixLongBranches can relax BCS > 127 bytes!
                     List<AsmLine> dispatchLines = new ArrayList<>();
                     if(minVal > 0) {
                        AsmInstruction secInst = new AsmInstruction(secOp, null, null, null);
                        secInst.getTags().add("nooptimize");
                        AsmInstruction sbcInst = new AsmInstruction(sbcImm, String.valueOf(minVal), null, null);
                        sbcInst.getTags().add("nooptimize");
                        dispatchLines.add(secInst);
                        dispatchLines.add(sbcInst);
                     }
                     AsmInstruction cmpInst = new AsmInstruction(cmpImm, String.valueOf(span), null, null);
                     cmpInst.getTags().add("nooptimize");
                     dispatchLines.add(cmpInst);
                     // Do NOT tag BCS as nooptimize so Pass5FixLongBranches can expand/relax it when > 127 bytes
                     dispatchLines.add(new AsmInstruction(bcsRel, defLabelForTable, null, null));

                     AsmInstruction taxInst = new AsmInstruction(taxOp, null, null, null);
                     taxInst.getTags().add("nooptimize");
                     AsmInstruction ldaHiInst = new AsmInstruction(ldaAbx, tableHiName, null, null);
                     ldaHiInst.getTags().add("nooptimize");
                     AsmInstruction phaHiInst = new AsmInstruction(phaOp, null, null, null);
                     phaHiInst.getTags().add("nooptimize");
                     AsmInstruction ldaLoInst = new AsmInstruction(ldaAbx, tableLoName, null, null);
                     ldaLoInst.getTags().add("nooptimize");
                     AsmInstruction phaLoInst = new AsmInstruction(phaOp, null, null, null);
                     phaLoInst.getTags().add("nooptimize");
                     AsmInstruction rtsInst = new AsmInstruction(rtsOp, null, null, null);
                     rtsInst.getTags().add("nooptimize");
                     dispatchLines.add(taxInst);
                     dispatchLines.add(ldaHiInst);
                     dispatchLines.add(phaHiInst);
                     dispatchLines.add(ldaLoInst);
                     dispatchLines.add(phaLoInst);
                     dispatchLines.add(rtsInst);

                     AsmDataKickAsm loTableLine = new AsmDataKickAsm(tableLoName, span, ".byte " + loBuilder.toString());
                     AsmDataKickAsm hiTableLine = new AsmDataKickAsm(tableHiName, span, ".byte " + hiBuilder.toString());

                     // First line ref where dispatch will be inserted
                     LineRef firstRef = chain.get(0).linesToRemove.get(0);
                     int insertPos = firstRef.chunk.getLines().indexOf(firstRef.line);
                     if(insertPos == -1) insertPos = 0;

                     // Remove all old comparison/branch lines from their chunks
                     for(CaseBranch cb : chain) {
                        for(LineRef lr : cb.linesToRemove) {
                           lr.chunk.getLines().remove(lr.line);
                        }
                     }

                     // If default had an explicit jmp, remove it too
                     if(jmpDefRef != null) {
                        jmpDefRef.chunk.getLines().remove(jmpDefRef.line);
                     }

                     // Protect target labels strictly within chainScope from Pass5UnusedLabelElimination & Pass5DuplicateBlockElimination
                     for(CaseBranch cb : chain) {
                        Integer tIdx = labelLineMap.get(chainScope + "::" + cb.targetLabel);
                        if(tIdx != null) {
                           allLines.get(tIdx).line.getTags().add("nooptimize");
                        }
                     }
                     if(defaultTarget != null && !defaultIsFallthrough) {
                        Integer defIdx = labelLineMap.get(chainScope + "::" + defaultTarget);
                        if(defIdx != null) {
                           allLines.get(defIdx).line.getTags().add("nooptimize");
                        }
                     }

                     // Insert dispatch instructions and tables into first chunk
                     for(AsmLine dl : dispatchLines) {
                        firstRef.chunk.getLines().add(insertPos++, dl);
                     }
                     firstRef.chunk.getLines().add(insertPos++, loTableLine);
                     firstRef.chunk.getLines().add(insertPos++, hiTableLine);

                     if(defaultIsFallthrough) {
                        AsmLabel defLabelLine = new AsmLabel("__sw_def_" + tableId);
                        defLabelLine.getTags().add("nooptimize");
                        firstRef.chunk.getLines().add(insertPos++, defLabelLine);
                     }

                     getLog().append("Pass 5: Converted switch with " + chain.size() + " cases (span=" + span + ") to Jump Table " + tableLoName);
                     roundModified = true;
                     modified = true;
                     break; // Break and rebuild allLines
                  }
               }
            }
         }

         if(!roundModified) break;
      }

      return modified;
   }

   private List<CaseBranch> matchCaseChain(List<LineRef> allLines, int startIdx) {
      List<CaseBranch> chain = new ArrayList<>();
      int cur = startIdx;

      while(cur < allLines.size()) {
         // Skip comments, constants, label declarations
         while(cur < allLines.size() && isSkipLine(allLines.get(cur).line)) {
            cur++;
         }
         if(cur >= allLines.size()) break;

         // Check if CMP #imm
         if(!(allLines.get(cur).line instanceof AsmInstruction)) break;
         AsmInstruction cmpInst = (AsmInstruction) allLines.get(cur).line;
         if(cmpInst.getCpuOpcode() == null || !"cmp".equals(cmpInst.getCpuOpcode().getMnemonic())) break;
         if(cmpInst.getCpuOpcode().getAddressingMode() != CpuAddressingMode.IMM) break;

         Integer immVal = parseImmediate(cmpInst.getOperand1());
         if(immVal == null) break;

         LineRef cmpRef = allLines.get(cur);
         cur++;

         // Skip comments
         while(cur < allLines.size() && isSkipLine(allLines.get(cur).line)) {
            cur++;
         }
         if(cur >= allLines.size()) break;

         // Check branch: either BEQ target, or BNE !+; JMP target; !+:
         if(!(allLines.get(cur).line instanceof AsmInstruction)) break;
         AsmInstruction branchInst = (AsmInstruction) allLines.get(cur).line;
         if(branchInst.getCpuOpcode() == null) break;

         if("beq".equals(branchInst.getCpuOpcode().getMnemonic())) {
            String target = branchInst.getOperand1();
            if(target != null && !target.startsWith("!")) {
               List<LineRef> toRemove = new ArrayList<>();
               toRemove.add(cmpRef);
               toRemove.add(allLines.get(cur));
               chain.add(new CaseBranch(immVal, target, toRemove));
               cur++;
               continue;
            }
         } else if("bne".equals(branchInst.getCpuOpcode().getMnemonic()) && branchInst.getOperand1() != null && branchInst.getOperand1().startsWith("!")) {
            // Check long branch: BNE !+; JMP target; !+:
            LineRef bneRef = allLines.get(cur);
            int nextIdx = cur + 1;
            while(nextIdx < allLines.size() && isSkipLine(allLines.get(nextIdx).line)) nextIdx++;
            if(nextIdx < allLines.size() && allLines.get(nextIdx).line instanceof AsmInstruction) {
               AsmInstruction jmpInst = (AsmInstruction) allLines.get(nextIdx).line;
               if(jmpInst.getCpuOpcode() != null && "jmp".equals(jmpInst.getCpuOpcode().getMnemonic())) {
                  String target = jmpInst.getOperand1();
                  int lblIdx = nextIdx + 1;
                  while(lblIdx < allLines.size() && isSkipLine(allLines.get(lblIdx).line)) lblIdx++;
                  if(lblIdx < allLines.size() && allLines.get(lblIdx).line instanceof AsmLabel && ((AsmLabel) allLines.get(lblIdx).line).getLabel().startsWith("!")) {
                     List<LineRef> toRemove = new ArrayList<>();
                     toRemove.add(cmpRef);
                     toRemove.add(bneRef);
                     toRemove.add(allLines.get(nextIdx));
                     toRemove.add(allLines.get(lblIdx));
                     chain.add(new CaseBranch(immVal, target, toRemove));
                     cur = lblIdx + 1;
                     continue;
                  }
               }
            }
         }

         break; // End of chain
      }

      return chain;
   }

   private static boolean isSkipLine(AsmLine line) {
      return line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl;
   }

   private static Integer parseImmediate(String operand) {
      if(operand == null) return null;
      String s = operand.trim();
      if(s.startsWith("#")) s = s.substring(1).trim();
      if(s.startsWith("$")) {
         try {
            return Integer.parseInt(s.substring(1), 16);
         } catch(NumberFormatException e) {
            return null;
         }
      }
      try {
         return Integer.parseInt(s);
      } catch(NumberFormatException e) {
         return null;
      }
   }

   /**
    * Verifies that register 'reg' (A or X) is not read before being overwritten in targetLabel within scope.
    */
   private boolean isRegisterSafeForTarget(List<LineRef> allLines, Map<String, Integer> labelMap, String scope, String targetLabel, String reg) {
      String scopedKey = (scope != null ? scope : "") + "::" + targetLabel;
      Integer targetIdx = labelMap.get(scopedKey);
      if(targetIdx == null) return false;
      return isRegisterSafeFromIndex(allLines, labelMap, scope, targetIdx + 1, reg, new HashSet<>(), 0);
   }

   private boolean isRegisterSafeFromIndex(List<LineRef> allLines, Map<String, Integer> labelMap, String scope, int startIdx, String reg, Set<Integer> visited, int branchDepth) {
      for(int i = startIdx; i < allLines.size() && (i - startIdx) < 60; i++) {
         if(!visited.add(i)) {
            return false; // Cycle reached without proving reg dead -> conservative
         }
         LineRef ref = allLines.get(i);
         AsmLine line = ref.line;
         if(line instanceof AsmScopeEnd || line instanceof AsmScopeBegin || line instanceof AsmInlineKickAsm) {
            return false;
         }
         if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl || line instanceof AsmLabel) {
            continue;
         }
         if(line instanceof AsmInstruction inst) {
            if(inst.getCpuOpcode() == null) return false;
            String mnem = inst.getCpuOpcode().getMnemonic();
            CpuAddressingMode mode = inst.getCpuOpcode().getAddressingMode();

            if(readsRegister(inst, reg)) {
               return false; // Register is read before overwrite -> NOT SAFE!
            }
            if(overwritesRegister(inst, reg)) {
               return true; // Register is overwritten before read -> SAFE!
            }
            if("rts".equals(mnem)) {
               if("A".equals(reg)) {
                  if(scope != null && !scope.isEmpty() && getProgram() != null && getProgram().getScope() != null) {
                     Symbol sym = getProgram().getScope().getLocalSymbol(scope);
                     if(sym instanceof Procedure proc && SymbolType.VOID.equals(proc.getReturnType())) {
                        return true;
                     }
                  }
                  return false;
               }
               if("X".equals(reg)) {
                  if(scope != null && !scope.isEmpty() && getProgram() != null && getProgram().getScope() != null) {
                     Symbol sym = getProgram().getScope().getLocalSymbol(scope);
                     if(sym instanceof Procedure proc && proc.isAxConvention()) {
                        return false;
                     }
                  }
                  return true;
               }
               return false;
            }
            if("rti".equals(mnem)) {
               return false;
            }
            if("jmp".equals(mnem)) {
               if(mode == CpuAddressingMode.IND || mode == CpuAddressingMode.IAX || branchDepth >= 4) {
                  return false;
               }
               String tgt = inst.getOperandJumpTarget();
               if(tgt == null) return false;
               int tgtIdx = tgt.startsWith("!")
                     ? findLabelIndex(allLines, tgt, i, scope)
                     : labelMap.getOrDefault((scope != null ? scope : "") + "::" + tgt, -1);
               if(tgtIdx == -1) return false;
               return isRegisterSafeFromIndex(allLines, labelMap, scope, tgtIdx + 1, reg, visited, branchDepth + 1);
            }
            if(inst.getCpuOpcode().isJump()) {
               // Conditional branch: reg must be safe on BOTH fallthrough and branch target
               if(branchDepth >= 3) return false;
               String tgt = inst.getOperandJumpTarget();
               if(tgt == null) return false;
               int tgtIdx = tgt.startsWith("!")
                     ? findLabelIndex(allLines, tgt, i, scope)
                     : labelMap.getOrDefault((scope != null ? scope : "") + "::" + tgt, -1);
               if(tgtIdx == -1) return false;
               return isRegisterSafeFromIndex(allLines, labelMap, scope, i + 1, reg, new HashSet<>(visited), branchDepth + 1)
                   && isRegisterSafeFromIndex(allLines, labelMap, scope, tgtIdx + 1, reg, new HashSet<>(visited), branchDepth + 1);
            }
         }
      }
      return false; // Conservative if scan limit or end reached without proving reg dead
   }

   private static boolean readsRegister(AsmInstruction inst, String reg) {
      if(inst == null || inst.getCpuOpcode() == null) return true;
      String mnem = inst.getCpuOpcode().getMnemonic();
      CpuAddressingMode mode = inst.getCpuOpcode().getAddressingMode();

      if("A".equals(reg)) {
         if(mnem.equals("sta") || mnem.equals("sax") || mnem.equals("pha") ||
            mnem.equals("tax") || mnem.equals("tay") || mnem.equals("cmp") ||
            mnem.equals("adc") || mnem.equals("sbc") || mnem.equals("and") ||
            mnem.equals("ora") || mnem.equals("eor") || mnem.equals("bit") ||
            mnem.equals("dcp") || mnem.equals("isc") || mnem.equals("slo") ||
            mnem.equals("rla") || mnem.equals("sre") || mnem.equals("rra") ||
            mnem.equals("alr") || mnem.equals("arr") || mnem.equals("anc") ||
            mnem.equals("axs") || mnem.equals("jsr") || mnem.equals("rti")) {
            return true;
         }
         if((mnem.equals("asl") || mnem.equals("lsr") || mnem.equals("rol") || mnem.equals("ror")) &&
            (mode.equals(CpuAddressingMode.NON) || "a".equalsIgnoreCase(inst.getOperand1()))) {
            return true;
         }
         return false;
      } else if("X".equals(reg)) {
         if(mnem.equals("stx") || mnem.equals("sax") || mnem.equals("txa") || mnem.equals("txs") ||
            mnem.equals("cpx") || mnem.equals("dex") || mnem.equals("inx") ||
            mnem.equals("axs") || mnem.equals("phx") || mnem.equals("jsr") || mnem.equals("rti")) {
            return true;
         }
         // Check indexed/indirect addressing modes reading X via CpuAddressingMode
         if(mode == CpuAddressingMode.ABX || mode == CpuAddressingMode.ZPX ||
            mode == CpuAddressingMode.IZX || mode == CpuAddressingMode.IAX) {
            return true;
         }
         return false;
      }
      return true;
   }

   private static boolean overwritesRegister(AsmInstruction inst, String reg) {
      if(inst == null || inst.getCpuOpcode() == null) return false;
      String mnem = inst.getCpuOpcode().getMnemonic();
      if("A".equals(reg)) {
         return mnem.equals("lda") || mnem.equals("pla") || mnem.equals("lax") ||
                mnem.equals("txa") || mnem.equals("tya");
      } else if("X".equals(reg)) {
         return mnem.equals("ldx") || mnem.equals("lax") || mnem.equals("tax") || mnem.equals("tsx");
      }
      return false;
   }
}
