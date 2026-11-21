package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;

import java.util.*;

/**
 * Assembler Peephole Optimization: Alias-Aware Redundant Store & Reload Elimination.
 * 1. Redundant Reload Elimination:
 *    When A (or X, Y) was loaded from RAM variable SRC, and intervening instructions
 *    do not modify the register or SRC (e.g. stores to distinct variables), a subsequent
 *    load of SRC is completely redundant.
 * 2. Alias-Aware Self-Store Elimination:
 *    When A (or X, Y) was loaded from RAM variable SRC, and an instruction stores to DST
 *    where canonical(DST) == canonical(SRC) (resolved via .label declarations and aliases)
 *    and DST is not a hardware I/O register, the store is storing back into itself and is redundant.
 */
public class Pass5RedundantStoreAndReloadElimination extends Pass5AsmOptimization {

   private static final java.util.regex.Pattern WHITESPACE_PATTERN = java.util.regex.Pattern.compile("\\s+");



   public Pass5RedundantStoreAndReloadElimination(Program program) {
      super(program);
   }

   public static boolean isHardwareAddress(String op) {
      return Pass5AsmOptimization.isHardwareAddress(op);
   }



   public static String resolveAlias(String name, Map<String, String> scopeAliases, Map<String, String> globalAliases) {
      if(name == null) return "";
      String current = name.trim();
      for(int depth = 0; depth < 10; depth++) {
         if(scopeAliases != null && scopeAliases.containsKey(current)) {
            current = scopeAliases.get(current).trim();
         } else if(globalAliases != null && globalAliases.containsKey(current)) {
            current = globalAliases.get(current).trim();
         } else {
            break;
         }
      }
      return current;
   }

   public static String getCanonical(String op, Map<String, String> scopeAliases, Map<String, String> globalAliases) {
      if(op == null) return "";
      String trimmed = op.trim();
      int plusIdx = trimmed.indexOf('+');
      int minusIdx = trimmed.indexOf('-');
      int splitIdx = -1;
      if(plusIdx != -1 && minusIdx != -1) splitIdx = Math.min(plusIdx, minusIdx);
      else if(plusIdx != -1) splitIdx = plusIdx;
      else if(minusIdx != -1) splitIdx = minusIdx;

      if(splitIdx == -1) {
         return resolveAlias(trimmed, scopeAliases, globalAliases);
      } else {
         String base = trimmed.substring(0, splitIdx).trim();
         String suffix = WHITESPACE_PATTERN.matcher(trimmed.substring(splitIdx)).replaceAll("");
         return resolveAlias(base, scopeAliases, globalAliases) + suffix;
      }
   }

   public static boolean isSameLocation(String op1, String op2, Map<String, String> scopeAliases, Map<String, String> globalAliases) {
      if(op1 == null || op2 == null) return false;
      return getCanonical(op1, scopeAliases, globalAliases).equals(getCanonical(op2, scopeAliases, globalAliases));
   }

   @Override
   public boolean optimize() {
      boolean overallModified = false;
      boolean passModified = true;

      while(passModified) {
         passModified = false;

         List<LineRef> allLines = flattenLines(getAsmProgram());
         Map<String, CanonicalResolver> scopeResolvers = buildScopeResolvers(allLines);
         Map<String, Map<String, String>> scopeAliasMap = new HashMap<>();
         Map<String, String> globalAliasMap = new HashMap<>();
         for(LineRef ref : allLines) {
            if(ref.line instanceof AsmLabelDecl) {
               AsmLabelDecl decl = (AsmLabelDecl) ref.line;
               String rawName = decl.getName();
               String rawVal = decl.getValue();
               if(ref.scope != null && !ref.scope.isEmpty()) {
                  scopeAliasMap.computeIfAbsent(ref.scope, k -> new HashMap<>()).put(rawName, rawVal);
                  String qualName = rawName.contains(".") ? rawName : (ref.scope + "." + rawName);
                  String qualVal = (rawVal.contains(".") || rawVal.startsWith("$") || rawVal.startsWith("#")) ? rawVal : (ref.scope + "." + rawVal);
                  globalAliasMap.put(qualName, qualVal);
               } else {
                  globalAliasMap.put(rawName, rawVal);
               }
            }
         }

         String currentScope = "";
         // Track canonical memory locations currently held in registers
         Set<String> aMems = new HashSet<>();
         Set<String> xMems = new HashSet<>();
         Set<String> yMems = new HashSet<>();

         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref = allLines.get(i);
            if(ref.line instanceof AsmScopeBegin) {
               currentScope = ((AsmScopeBegin) ref.line).getLabel();
               aMems.clear();
               xMems.clear();
               yMems.clear();
               continue;
            } else if(ref.line instanceof AsmScopeEnd) {
               currentScope = "";
               aMems.clear();
               xMems.clear();
               yMems.clear();
               continue;
            } else if(ref.line instanceof AsmLabel || ref.line instanceof AsmInlineKickAsm) {
               aMems.clear();
               xMems.clear();
               yMems.clear();
               continue;
            }

            if(ref.line instanceof AsmComment || ref.line instanceof AsmConstant || ref.line instanceof AsmLabelDecl) {
               continue;
            }

            if(!(ref.line instanceof AsmInstruction)) {
               aMems.clear();
               xMems.clear();
               yMems.clear();
               continue;
            }

            AsmInstruction inst = (AsmInstruction) ref.line;
            if(inst.isNoOptimize() || inst.getCpuOpcode() == null) {
               aMems.clear();
               xMems.clear();
               yMems.clear();
               continue;
            }

            Map<String, String> localAliases = scopeAliasMap.get(currentScope);
            CanonicalResolver resolver = scopeResolvers.getOrDefault(currentScope, scopeResolvers.get(""));
            CpuOpcode op = inst.getCpuOpcode();
            String mnem = op.getMnemonic();
            CpuAddressingMode mode = op.getAddressingMode();
            String operand = inst.getOperand1();

            // -----------------------------------------------------------------
            // Control flow breaks (unconditional jump, subroutine call, returns) reset tracking
            // -----------------------------------------------------------------
            if(mnem.equals("jmp") || mnem.equals("rts") || mnem.equals("rti") || mnem.equals("jsr")) {
               aMems.clear();
               xMems.clear();
               yMems.clear();
               continue;
            }

            // -----------------------------------------------------------------
            // 1. LDA
            // -----------------------------------------------------------------
            if(mnem.equals("lda")) {
               if(mode.equals(CpuAddressingMode.ZP) || mode.equals(CpuAddressingMode.ABS)) {
                  if(!isVolatileOrHardwareOperand(operand, currentScope, resolver, getProgram())) {
                     String canon = getCanonical(operand, localAliases, globalAliasMap);
                     if(aMems.contains(canon)) {
                        if(!areModifiedFlagsLiveAfter(allLines, i, currentScope, getProgram()) || areFlagsEstablishedForReg(allLines, i, "A", currentScope)) {
                           getLog().append("Eliminating redundant load " + inst.getAsm() + " (A already contains " + canon + ")");
                           ref.chunk.getLines().remove(inst);
                           passModified = true;
                           overallModified = true;
                           break;
                        }
                     }
                     aMems.clear();
                     aMems.add(canon);
                     continue;
                  }
               }
               if(!aMems.isEmpty() && !isVolatileOrHardwareOperand(operand, currentScope, resolver, getProgram())) {
                  // FL-05: Commutative accumulator forwarding: STA/LDA X; LDA Y; [CLC/SEC;] ORA/AND/EOR/ADC X -> [CLC/SEC;] ORA/AND/EOR/ADC Y
                  int nextIdx = findNextInstruction(allLines, i + 1);
                  if(nextIdx != -1) {
                     AsmInstruction nextInst = (AsmInstruction) allLines.get(nextIdx).line;
                     String nextMnem = nextInst.getCpuOpcode() != null ? nextInst.getCpuOpcode().getMnemonic() : "";
                     if(nextMnem.equals("clc") || nextMnem.equals("sec")) {
                        nextIdx = findNextInstruction(allLines, nextIdx + 1);
                        nextInst = nextIdx != -1 ? (AsmInstruction) allLines.get(nextIdx).line : null;
                        nextMnem = (nextInst != null && nextInst.getCpuOpcode() != null) ? nextInst.getCpuOpcode().getMnemonic() : "";
                     }
                     if(nextInst != null && (nextMnem.equals("ora") || nextMnem.equals("and") || nextMnem.equals("eor") || nextMnem.equals("adc"))) {
                        CpuAddressingMode nextMode = nextInst.getCpuOpcode().getAddressingMode();
                        String nextOp = nextInst.getOperand1();
                        if((nextMode.equals(CpuAddressingMode.ZP) || nextMode.equals(CpuAddressingMode.ABS))
                              && !isVolatileOrHardwareOperand(nextOp, currentScope, resolver, getProgram())) {
                           String nextCanon = getCanonical(nextOp, localAliases, globalAliasMap);
                           if(aMems.contains(nextCanon)) {
                              CpuOpcode newOp = getProgram().getTargetCpu().getCpu65xx().getOpcode(nextMnem, mode, false);
                              if(newOp != null) {
                                 getLog().append("Commutative accumulator forwarding: " + inst.getAsm() + " ; " + nextInst.getAsm() + " -> " + nextMnem + " " + operand);
                                 nextInst.setCpuOpcode(newOp);
                                 nextInst.setOperand1(operand);
                                 nextInst.setOperand2(inst.getOperand2());
                                 ref.chunk.getLines().remove(inst);
                                 passModified = true;
                                 overallModified = true;
                                 break;
                              }
                           }
                        }
                     }
                  }
               }
               aMems.clear();
               continue;
            }

            // -----------------------------------------------------------------
            // 2. STA
            // -----------------------------------------------------------------
            if(mnem.equals("sta")) {
               if(mode.equals(CpuAddressingMode.ZP) || mode.equals(CpuAddressingMode.ABS)) {
                  if(!isVolatileOrHardwareOperand(operand, currentScope, resolver, getProgram())) {
                     String canon = getCanonical(operand, localAliases, globalAliasMap);
                     if(aMems.contains(canon)) {
                        getLog().append("Eliminating redundant self-store " + inst.getAsm() + " (A already loaded from " + canon + ")");
                        ref.chunk.getLines().remove(inst);
                        passModified = true;
                        overallModified = true;
                        break;
                     }
                     // Memory at operand was overwritten; invalidate any other registers holding it
                     xMems.remove(canon);
                     yMems.remove(canon);
                     // A still holds whatever it had, AND now also matches operand
                     aMems.add(canon);
                     continue;
                  }
               }
               continue;
            }

            // -----------------------------------------------------------------
            // 3. LDX
            // -----------------------------------------------------------------
            if(mnem.equals("ldx")) {
               if(mode.equals(CpuAddressingMode.ZP) || mode.equals(CpuAddressingMode.ABS)) {
                  if(!isVolatileOrHardwareOperand(operand, currentScope, resolver, getProgram())) {
                     String canon = getCanonical(operand, localAliases, globalAliasMap);
                     if(xMems.contains(canon)) {
                        if(!areModifiedFlagsLiveAfter(allLines, i, currentScope, getProgram()) || areFlagsEstablishedForReg(allLines, i, "X", currentScope)) {
                           getLog().append("Eliminating redundant load " + inst.getAsm() + " (X already contains " + canon + ")");
                           ref.chunk.getLines().remove(inst);
                           passModified = true;
                           overallModified = true;
                           break;
                        }
                     }
                     xMems.clear();
                     xMems.add(canon);
                     continue;
                  }
               }
               xMems.clear();
               continue;
            }

            // -----------------------------------------------------------------
            // 4. STX
            // -----------------------------------------------------------------
            if(mnem.equals("stx")) {
               if(mode.equals(CpuAddressingMode.ZP) || mode.equals(CpuAddressingMode.ABS)) {
                  if(!isVolatileOrHardwareOperand(operand, currentScope, resolver, getProgram())) {
                     String canon = getCanonical(operand, localAliases, globalAliasMap);
                     if(xMems.contains(canon)) {
                        getLog().append("Eliminating redundant self-store " + inst.getAsm() + " (X already loaded from " + canon + ")");
                        ref.chunk.getLines().remove(inst);
                        passModified = true;
                        overallModified = true;
                        break;
                     }
                     aMems.remove(canon);
                     yMems.remove(canon);
                     xMems.add(canon);
                     continue;
                  }
               }
               continue;
            }

            // -----------------------------------------------------------------
            // 5. LDY
            // -----------------------------------------------------------------
            if(mnem.equals("ldy")) {
               if(mode.equals(CpuAddressingMode.ZP) || mode.equals(CpuAddressingMode.ABS)) {
                  if(!isVolatileOrHardwareOperand(operand, currentScope, resolver, getProgram())) {
                     String canon = getCanonical(operand, localAliases, globalAliasMap);
                     if(yMems.contains(canon)) {
                        if(!areModifiedFlagsLiveAfter(allLines, i, currentScope, getProgram()) || areFlagsEstablishedForReg(allLines, i, "Y", currentScope)) {
                           getLog().append("Eliminating redundant load " + inst.getAsm() + " (Y already contains " + canon + ")");
                           ref.chunk.getLines().remove(inst);
                           passModified = true;
                           overallModified = true;
                           break;
                        }
                     }
                     yMems.clear();
                     yMems.add(canon);
                     continue;
                  }
               }
               yMems.clear();
               continue;
            }

            // -----------------------------------------------------------------
            // 6. STY
            // -----------------------------------------------------------------
            if(mnem.equals("sty")) {
               if(mode.equals(CpuAddressingMode.ZP) || mode.equals(CpuAddressingMode.ABS)) {
                  if(!isVolatileOrHardwareOperand(operand, currentScope, resolver, getProgram())) {
                     String canon = getCanonical(operand, localAliases, globalAliasMap);
                     if(yMems.contains(canon)) {
                        getLog().append("Eliminating redundant self-store " + inst.getAsm() + " (Y already loaded from " + canon + ")");
                        ref.chunk.getLines().remove(inst);
                        passModified = true;
                        overallModified = true;
                        break;
                     }
                     aMems.remove(canon);
                     xMems.remove(canon);
                     yMems.add(canon);
                     continue;
                  }
               }
               continue;
            }

            // -----------------------------------------------------------------
            // Register-to-register transfers
            // -----------------------------------------------------------------
            if(mnem.equals("tax")) {
               xMems.clear();
               xMems.addAll(aMems);
               continue;
            }
            if(mnem.equals("txa")) {
               aMems.clear();
               aMems.addAll(xMems);
               continue;
            }
            if(mnem.equals("tay")) {
               yMems.clear();
               yMems.addAll(aMems);
               continue;
            }
            if(mnem.equals("tya")) {
               aMems.clear();
               aMems.addAll(yMems);
               continue;
            }

            // -----------------------------------------------------------------
            // Instructions that invalidate memory location directly
            // -----------------------------------------------------------------
            if(mnem.equals("inc") || mnem.equals("dec") || mnem.equals("asl") || mnem.equals("lsr") ||
               mnem.equals("rol") || mnem.equals("ror") || mnem.equals("dcp") || mnem.equals("isc") ||
               mnem.equals("slo") || mnem.equals("sre") || mnem.equals("rla") || mnem.equals("rra") ||
               mnem.equals("sax")) {
               if(mode.equals(CpuAddressingMode.NON)) {
                  // Acc shift / rot modifies A
                  aMems.clear();
                  continue;
               }
               if(operand != null) {
                  String canon = getCanonical(operand, localAliases, globalAliasMap);
                  aMems.remove(canon);
                  xMems.remove(canon);
                  yMems.remove(canon);
               }
               if(mnem.equals("dcp") || mnem.equals("isc") || mnem.equals("slo") || mnem.equals("sre") ||
                  mnem.equals("rla") || mnem.equals("rra")) {
                  aMems.clear();
               }
               continue;
            }

            // -----------------------------------------------------------------
            // Instructions modifying A
            // -----------------------------------------------------------------
            if(mnem.equals("pla") || mnem.equals("adc") || mnem.equals("sbc") || mnem.equals("and") ||
               mnem.equals("ora") || mnem.equals("eor") || mnem.equals("lax") || mnem.equals("alr") ||
               mnem.equals("anc") || mnem.equals("arr")) {
               aMems.clear();
               if(mnem.equals("lax")) {
                  xMems.clear();
                  if((mode.equals(CpuAddressingMode.ZP) || mode.equals(CpuAddressingMode.ABS)) && !isVolatileOrHardwareOperand(operand, currentScope, resolver, getProgram())) {
                     String canon = getCanonical(operand, localAliases, globalAliasMap);
                     aMems.add(canon);
                     xMems.add(canon);
                  }
               }
               continue;
            }

            // -----------------------------------------------------------------
            // Instructions modifying X
            // -----------------------------------------------------------------
            if(mnem.equals("inx") || mnem.equals("dex") || mnem.equals("tsx") || mnem.equals("axs")) {
               xMems.clear();
               continue;
            }

            // -----------------------------------------------------------------
            // Instructions modifying Y
            // -----------------------------------------------------------------
            if(mnem.equals("iny") || mnem.equals("dey")) {
               yMems.clear();
               continue;
            }

            // Other instructions: Comparisons (CMP, CPX, CPY, BIT) leave registers intact
            if(mnem.equals("cmp") || mnem.equals("cpx") || mnem.equals("cpy") || mnem.equals("bit") ||
               mnem.equals("clc") || mnem.equals("sec") || mnem.equals("cld") || mnem.equals("sed") ||
               mnem.equals("cli") || mnem.equals("sei") || mnem.equals("clv") || mnem.equals("nop") ||
               mnem.equals("pha") || mnem.equals("php") || mnem.equals("plp")) {
               continue;
            }

            // Fallback for unknown instructions: clear all
            aMems.clear();
            xMems.clear();
            yMems.clear();
         }
      }

      return overallModified;
   }
}
