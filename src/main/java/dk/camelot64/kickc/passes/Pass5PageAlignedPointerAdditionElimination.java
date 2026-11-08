package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Symbol;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.model.values.ConstantPointer;
import dk.camelot64.kickc.model.values.ConstantValue;

import java.util.*;

/**
 * Assembler Peephole Optimization: Page-Aligned 16-Bit Pointer Addition Folding.
 * Recognizes 16-bit addition sequences where the low byte of the constant operand is zero
 * (e.g. $d800, $6000, $5c00, COLORRAM, VIDEO_RAM_BITMAP, CARTRIDGE, etc.):
 *
 * Case 1: Self-Addition (SRC == DST)
 *   clc
 *   lda.z col
 *   adc #<$d800
 *   sta.z col
 *   lda.z col+1
 *   adc #>$d800
 *   sta.z col+1
 *   =>
 *   clc
 *   lda.z col+1
 *   adc #>$d800
 *   sta.z col+1
 *
 * Case 2: Different Source and Destination (SRC != DST)
 *   clc
 *   lda.z offset
 *   adc #<$5c00
 *   sta.z s
 *   lda.z offset+1
 *   adc #>$5c00
 *   sta.z s+1
 *   =>
 *   clc
 *   lda.z offset
 *   sta.z s
 *   lda.z offset+1
 *   adc #>$5c00
 *   sta.z s+1
 */
public class Pass5PageAlignedPointerAdditionElimination extends Pass5AsmOptimization {

   private static class LineRef {
      final AsmChunk chunk;
      final AsmLine line;

      LineRef(AsmChunk chunk, AsmLine line) {
         this.chunk = chunk;
         this.line = line;
      }
   }

   public Pass5PageAlignedPointerAdditionElimination(Program program) {
      super(program);
   }

   private Long parseSingleTerm(String term, Scope scope, Map<String, String> asmConstants, Map<String, String> localAliases, Map<String, String> globalAliases) {
      if(term == null) return null;
      String t = term.trim();
      if(t.isEmpty()) return null;

      if(t.startsWith("$")) {
         try {
            return Long.parseLong(t.substring(1), 16);
         } catch(Exception e) {
            return null;
         }
      }
      if(t.startsWith("0x") || t.startsWith("0X")) {
         try {
            return Long.parseLong(t.substring(2), 16);
         } catch(Exception e) {
            return null;
         }
      }
      if(Character.isDigit(t.charAt(0))) {
         try {
            return Long.parseLong(t, 10);
         } catch(Exception e) {
            return null;
         }
      }

      // Check ASM constants (.const NAME = VAL)
      if(asmConstants != null && asmConstants.containsKey(t)) {
         return evaluateConstantExpr(asmConstants.get(t), scope, asmConstants, localAliases, globalAliases);
      }

      // Check aliases (.label)
      if(localAliases != null && localAliases.containsKey(t)) {
         return evaluateConstantExpr(localAliases.get(t), scope, asmConstants, localAliases, globalAliases);
      }
      if(globalAliases != null && globalAliases.containsKey(t)) {
         return evaluateConstantExpr(globalAliases.get(t), scope, asmConstants, localAliases, globalAliases);
      }

      // Check Program scope symbols
      if(scope != null) {
         Symbol sym = scope.findSymbol(t);
         if(sym == null && getProgram() != null) {
            sym = getProgram().getScope().findSymbol(t);
         }
         if(sym instanceof Variable) {
            Variable v = (Variable) sym;
            if(v.isKindConstant() && v.getInitValue() != null) {
               ConstantValue cv = v.getInitValue();
               if(cv instanceof ConstantInteger) {
                  return ((ConstantInteger) cv).getValue();
               }
               if(cv instanceof ConstantPointer) {
                  return ((ConstantPointer) cv).getValue();
               }
            }
         }
      }

      return null;
   }

   private Long evaluateProductTerm(String term, Scope scope, Map<String, String> asmConstants, Map<String, String> localAliases, Map<String, String> globalAliases) {
      if(!term.contains("*")) {
         return parseSingleTerm(term, scope, asmConstants, localAliases, globalAliases);
      }
      String[] factors = term.split("\\*");
      long product = 1;
      for(String f : factors) {
         Long val = parseSingleTerm(f, scope, asmConstants, localAliases, globalAliases);
         if(val == null) return null;
         product *= val;
      }
      return product;
   }

   private Long evaluateConstantExpr(String expr, Scope scope, Map<String, String> asmConstants, Map<String, String> localAliases, Map<String, String> globalAliases) {
      if(expr == null) return null;
      String e = expr.trim();
      if(e.startsWith("(") && e.endsWith(")")) {
         e = e.substring(1, e.length() - 1).trim();
      }

      // Split additive components while respecting binary operations
      if(e.contains("+")) {
         String[] parts = e.split("\\+");
         long sum = 0;
         for(String p : parts) {
            Long val = evaluateProductTerm(p, scope, asmConstants, localAliases, globalAliases);
            if(val == null) return null;
            sum += val;
         }
         return sum;
      }

      if(e.contains("-") && !e.startsWith("-")) {
         String[] parts = e.split("-");
         Long first = evaluateProductTerm(parts[0], scope, asmConstants, localAliases, globalAliases);
         if(first == null) return null;
         long diff = first;
         for(int i = 1; i < parts.length; i++) {
            Long val = evaluateProductTerm(parts[i], scope, asmConstants, localAliases, globalAliases);
            if(val == null) return null;
            diff -= val;
         }
         return diff;
      }

      return evaluateProductTerm(e, scope, asmConstants, localAliases, globalAliases);
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
         Map<String, String> asmConstants = new HashMap<>();
         String scanScope = "";

         for(AsmChunk chunk : getAsmProgram().getChunks()) {
            for(AsmLine line : chunk.getLines()) {
               allLines.add(new LineRef(chunk, line));
               if(line instanceof AsmScopeBegin) {
                  scanScope = ((AsmScopeBegin) line).getLabel();
               } else if(line instanceof AsmScopeEnd) {
                  scanScope = "";
               } else if(line instanceof AsmConstant) {
                  AsmConstant ac = (AsmConstant) line;
                  if(ac.getName() != null && ac.getValue() != null) {
                     asmConstants.put(ac.getName().trim(), ac.getValue().trim());
                  }
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

         String currentScopeName = "";
         Scope currentScope = null;

         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref = allLines.get(i);
            if(ref.line instanceof AsmScopeBegin) {
               currentScopeName = ((AsmScopeBegin) ref.line).getLabel();
               currentScope = getProgram() != null ? (Scope) getProgram().getScope().getLocalSymbol(currentScopeName) : null;
               continue;
            } else if(ref.line instanceof AsmScopeEnd) {
               currentScopeName = "";
               currentScope = null;
               continue;
            }

            if(!(ref.line instanceof AsmInstruction)) continue;

            AsmInstruction instAdcLo = (AsmInstruction) ref.line;
            if(instAdcLo.isNoOptimize() || instAdcLo.getCpuOpcode() == null) continue;
            if(!instAdcLo.getCpuOpcode().getMnemonic().equals("adc")) continue;
            if(!CpuAddressingMode.IMM.equals(instAdcLo.getCpuOpcode().getAddressingMode())) continue;

            String opLo = instAdcLo.getOperand1();
            if(opLo == null) continue;
            String constExprLo;
            if(opLo.startsWith("#<")) constExprLo = opLo.substring(2).trim();
            else if(opLo.startsWith("<")) constExprLo = opLo.substring(1).trim();
            else continue;

            Map<String, String> localAliases = scopeAliasMap.get(currentScopeName);
            Long constVal = evaluateConstantExpr(constExprLo, currentScope, asmConstants, localAliases, globalAliasMap);
            if(constVal == null || (constVal.longValue() & 0xffL) != 0L) continue;

            // Found ADC #<CONST with low byte == 0.
            // Look forward for:
            // 1. sta(.z) DST_LO
            // 2. lda(.z) SRC_HI
            // 3. adc #>CONST
            // 4. sta(.z) DST_HI
            int idxStaLo = findNextInstruction(allLines, i + 1);
            if(idxStaLo == -1) continue;
            AsmInstruction instStaLo = (AsmInstruction) allLines.get(idxStaLo).line;
            if(instStaLo.getCpuOpcode() == null || !instStaLo.getCpuOpcode().getMnemonic().equals("sta")) continue;
            String dstLo = instStaLo.getOperand1();

            int idxLdaHi = findNextInstruction(allLines, idxStaLo + 1);
            if(idxLdaHi == -1) continue;
            AsmInstruction instLdaHi = (AsmInstruction) allLines.get(idxLdaHi).line;
            if(instLdaHi.getCpuOpcode() == null || !instLdaHi.getCpuOpcode().getMnemonic().equals("lda")) continue;
            String srcHi = instLdaHi.getOperand1();

            int idxAdcHi = findNextInstruction(allLines, idxLdaHi + 1);
            if(idxAdcHi == -1) continue;
            AsmInstruction instAdcHi = (AsmInstruction) allLines.get(idxAdcHi).line;
            if(instAdcHi.getCpuOpcode() == null || !instAdcHi.getCpuOpcode().getMnemonic().equals("adc")) continue;
            if(!CpuAddressingMode.IMM.equals(instAdcHi.getCpuOpcode().getAddressingMode())) continue;
            String opHi = instAdcHi.getOperand1();
            if(opHi == null) continue;
            String constExprHi;
            if(opHi.startsWith("#>")) constExprHi = opHi.substring(2).trim();
            else if(opHi.startsWith(">")) constExprHi = opHi.substring(1).trim();
            else continue;

            if(!constExprHi.equals(constExprLo)) {
               Long constValHi = evaluateConstantExpr(constExprHi, currentScope, asmConstants, localAliases, globalAliasMap);
               if(constValHi == null || !constValHi.equals(constVal)) continue;
            }

            int idxStaHi = findNextInstruction(allLines, idxAdcHi + 1);
            if(idxStaHi == -1) continue;
            AsmInstruction instStaHi = (AsmInstruction) allLines.get(idxStaHi).line;
            if(instStaHi.getCpuOpcode() == null || !instStaHi.getCpuOpcode().getMnemonic().equals("sta")) continue;
            String dstHi = instStaHi.getOperand1();

            // Verify DST_HI is DST_LO + 1
            if(!Pass5RedundantStoreAndReloadElimination.isSameLocation(dstLo + "+1", dstHi, localAliases, globalAliasMap)) {
               continue;
            }

            // Look backward from ADC #< for CLC and LDA SRC_LO
            int idxPrev1 = findPrevInstruction(allLines, i - 1);
            if(idxPrev1 == -1) continue;
            AsmInstruction instPrev1 = (AsmInstruction) allLines.get(idxPrev1).line;
            if(instPrev1.getCpuOpcode() == null) continue;

            String mnem1 = instPrev1.getCpuOpcode().getMnemonic();

            if(mnem1.equals("clc")) {
               // Pattern A: [idxPrev2: lda SRC_LO] -> [idxPrev1: clc] -> [i: adc #<]
               // OR: [idxPrev1: clc] with Accumulator already containing SRC_LO
               int idxPrev2 = findPrevInstruction(allLines, idxPrev1 - 1);
               if(idxPrev2 != -1 && allLines.get(idxPrev2).line instanceof AsmInstruction) {
                  AsmInstruction instPrev2 = (AsmInstruction) allLines.get(idxPrev2).line;
                  if(instPrev2.getCpuOpcode() != null && instPrev2.getCpuOpcode().getMnemonic().equals("lda")) {
                     String srcLo = instPrev2.getOperand1();
                     if(Pass5RedundantStoreAndReloadElimination.isSameLocation(srcLo + "+1", srcHi, localAliases, globalAliasMap)) {
                        if(Pass5RedundantStoreAndReloadElimination.isSameLocation(srcLo, dstLo, localAliases, globalAliasMap)) {
                           // Case 1: Self-Addition: lda SRC_LO; clc; adc #<0; sta SRC_LO; lda SRC_HI; adc #>C; sta DST_HI
                           // Remove lda SRC_LO, adc #<0, sta SRC_LO. clc remains in place right before lda SRC_HI!
                           allLines.get(idxPrev2).chunk.getLines().remove(instPrev2);
                           allLines.get(i).chunk.getLines().remove(instAdcLo);
                           allLines.get(idxStaLo).chunk.getLines().remove(instStaLo);
                           getLog().append("Eliminating redundant low-byte self-addition with 0 for " + srcLo + " (high-byte constant " + opHi + ")");
                           passModified = true;
                           overallModified = true;
                           break;
                        } else {
                           // Case 2: Different Destination: lda SRC_LO; clc; adc #<0; sta DST_LO
                           allLines.get(i).chunk.getLines().remove(instAdcLo);
                           getLog().append("Eliminating redundant ADC #<0 in pointer copy/addition to " + dstLo);
                           passModified = true;
                           overallModified = true;
                           break;
                        }
                     }
                  }
               }

               // Pattern C: clc; adc #<0; sta DST_LO (Accumulator already held SRC_LO)
               allLines.get(i).chunk.getLines().remove(instAdcLo);
               getLog().append("Eliminating redundant ADC #<0 in pointer addition to " + dstLo);
               passModified = true;
               overallModified = true;
               break;

            } else if(mnem1.equals("lda")) {
               // Pattern B: [idxPrev2: clc] -> [idxPrev1: lda SRC_LO] -> [i: adc #<]
               int idxPrev2 = findPrevInstruction(allLines, idxPrev1 - 1);
               if(idxPrev2 != -1 && allLines.get(idxPrev2).line instanceof AsmInstruction) {
                  AsmInstruction instPrev2 = (AsmInstruction) allLines.get(idxPrev2).line;
                  if(instPrev2.getCpuOpcode() != null && instPrev2.getCpuOpcode().getMnemonic().equals("clc")) {
                     String srcLo = instPrev1.getOperand1();
                     if(Pass5RedundantStoreAndReloadElimination.isSameLocation(srcLo + "+1", srcHi, localAliases, globalAliasMap)) {
                        if(Pass5RedundantStoreAndReloadElimination.isSameLocation(srcLo, dstLo, localAliases, globalAliasMap)) {
                           // Case 1: Self-Addition: clc; lda SRC_LO; adc #<0; sta SRC_LO; lda SRC_HI; adc #>C; sta DST_HI
                           // Remove lda SRC_LO, adc #<0, sta SRC_LO. clc remains in place directly before lda SRC_HI!
                           allLines.get(idxPrev1).chunk.getLines().remove(instPrev1);
                           allLines.get(i).chunk.getLines().remove(instAdcLo);
                           allLines.get(idxStaLo).chunk.getLines().remove(instStaLo);
                           getLog().append("Eliminating redundant low-byte self-addition with 0 for " + srcLo + " (high-byte constant " + opHi + ")");
                           passModified = true;
                           overallModified = true;
                           break;
                        } else {
                           // Case 2: Different Destination: clc; lda SRC_LO; adc #<0; sta DST_LO
                           allLines.get(i).chunk.getLines().remove(instAdcLo);
                           getLog().append("Eliminating redundant ADC #<0 in pointer copy/addition to " + dstLo);
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

   private static int findNextInstruction(List<LineRef> allLines, int startIdx) {
      for(int k = startIdx; k < allLines.size(); k++) {
         AsmLine line = allLines.get(k).line;
         if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl) continue;
         if(line instanceof AsmLabel || line instanceof AsmScopeBegin || line instanceof AsmScopeEnd || line instanceof AsmInlineKickAsm) {
            return -1;
         }
         if(line instanceof AsmInstruction) {
            return k;
         }
      }
      return -1;
   }

   private static int findPrevInstruction(List<LineRef> allLines, int startIdx) {
      for(int k = startIdx; k >= 0; k--) {
         AsmLine line = allLines.get(k).line;
         if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl) continue;
         if(line instanceof AsmLabel || line instanceof AsmScopeBegin || line instanceof AsmScopeEnd || line instanceof AsmInlineKickAsm) {
            return -1;
         }
         if(line instanceof AsmInstruction) {
            return k;
         }
      }
      return -1;
   }
}
