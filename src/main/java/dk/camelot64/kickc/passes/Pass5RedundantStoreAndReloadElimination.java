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

   private static class LineRef {
      final AsmChunk chunk;
      final AsmLine line;

      LineRef(AsmChunk chunk, AsmLine line) {
         this.chunk = chunk;
         this.line = line;
      }
   }

   public Pass5RedundantStoreAndReloadElimination(Program program) {
      super(program);
   }

   public static boolean isHardwareAddress(String op) {
      if(op == null) return false;
      String t = op.trim().toLowerCase();
      if(t.startsWith("$")) {
         try {
            int addr = Integer.parseInt(t.substring(1), 16);
            if((addr >= 0xd000 && addr <= 0xdfff) || (addr >= 0xde00 && addr <= 0xdfff)) {
               return true;
            }
         } catch(Exception e) {}
      }
      if(t.startsWith("0x")) {
         try {
            int addr = Integer.parseInt(t.substring(2), 16);
            if((addr >= 0xd000 && addr <= 0xdfff) || (addr >= 0xde00 && addr <= 0xdfff)) {
               return true;
            }
         } catch(Exception e) {}
      }
      if(t.contains("bank_reg") || t.contains("vicii") || t.contains("sid") || t.contains("cia") || t.contains("colorram")) {
         return true;
      }
      return false;
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
         String suffix = trimmed.substring(splitIdx).replaceAll("\\s+", "");
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

         List<LineRef> allLines = new ArrayList<>();
         Map<String, Map<String, String>> scopeAliasMap = new HashMap<>();
         Map<String, String> globalAliasMap = new HashMap<>();
         String scanScope = "";

         for(AsmChunk chunk : getAsmProgram().getChunks()) {
            for(AsmLine line : chunk.getLines()) {
               allLines.add(new LineRef(chunk, line));
               if(line instanceof AsmScopeBegin) {
                  scanScope = ((AsmScopeBegin) line).getLabel();
               } else if(line instanceof AsmScopeEnd) {
                  scanScope = "";
               } else if(line instanceof AsmLabelDecl) {
                  AsmLabelDecl decl = (AsmLabelDecl) line;
                  String rawName = decl.getName();
                  String rawVal = decl.getValue();
                  if(scanScope != null && !scanScope.isEmpty()) {
                     scopeAliasMap.computeIfAbsent(scanScope, k -> new HashMap<>()).put(rawName, rawVal);
                     String qualName = rawName.contains(".") ? rawName : (scanScope + "." + rawName);
                     String qualVal = (rawVal.contains(".") || rawVal.startsWith("$") || rawVal.startsWith("#")) ? rawVal : (scanScope + "." + rawVal);
                     globalAliasMap.put(qualName, qualVal);
                  } else {
                     globalAliasMap.put(rawName, rawVal);
                  }
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
                  if(!isHardwareAddress(operand)) {
                     String canon = getCanonical(operand, localAliases, globalAliasMap);
                     if(aMems.contains(canon)) {
                        getLog().append("Eliminating redundant load " + inst.getAsm() + " (A already contains " + canon + ")");
                        ref.chunk.getLines().remove(inst);
                        passModified = true;
                        overallModified = true;
                        break;
                     }
                     aMems.clear();
                     aMems.add(canon);
                     continue;
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
                  if(!isHardwareAddress(operand)) {
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
                  if(!isHardwareAddress(operand)) {
                     String canon = getCanonical(operand, localAliases, globalAliasMap);
                     if(xMems.contains(canon)) {
                        getLog().append("Eliminating redundant load " + inst.getAsm() + " (X already contains " + canon + ")");
                        ref.chunk.getLines().remove(inst);
                        passModified = true;
                        overallModified = true;
                        break;
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
                  if(!isHardwareAddress(operand)) {
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
                  if(!isHardwareAddress(operand)) {
                     String canon = getCanonical(operand, localAliases, globalAliasMap);
                     if(yMems.contains(canon)) {
                        getLog().append("Eliminating redundant load " + inst.getAsm() + " (Y already contains " + canon + ")");
                        ref.chunk.getLines().remove(inst);
                        passModified = true;
                        overallModified = true;
                        break;
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
                  if(!isHardwareAddress(operand)) {
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
                  if((mode.equals(CpuAddressingMode.ZP) || mode.equals(CpuAddressingMode.ABS)) && !isHardwareAddress(operand)) {
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
