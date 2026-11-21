package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.Registers;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;

import java.util.*;

/**
 * GEN-06: Auto-A-Register Return ABI optimization for 8-bit procedures whose return variable
 * remained in zero page after Pass 4 register allocation.
 *
 * Callee:  ... ; sta.z return ; rts   ->   ... ; rts   (or stx/sty return -> txa/tya)
 * Caller:  jsr fn ; lda.z fn.return   ->   jsr fn
 */
public class Pass5ARegisterReturnOptimization extends Pass5AsmOptimization {

   public Pass5ARegisterReturnOptimization(Program program) {
      super(program);
   }

   @Override
   public boolean optimize() {
      if(getProgram().getScope() == null) return false;
      List<LineRef> allLines = flattenLines(getAsmProgram());
      if(allLines.isEmpty()) return false;
      Map<String, CanonicalResolver> scopeResolvers = buildScopeResolvers(allLines);

      // Map procedure name -> scope range in allLines, and collect per-scope .label declarations
      Map<String, int[]> scopeRanges = new LinkedHashMap<>();
      Map<String, Map<String, String>> scopeLabelDecls = new HashMap<>();
      int scopeStart = -1;
      String curScope = null;
      for(int i = 0; i < allLines.size(); i++) {
         LineRef ref = allLines.get(i);
         AsmLine l = ref.line;
         if(l instanceof AsmScopeBegin sb) {
            curScope = sb.getLabel();
            scopeStart = i;
         } else if(l instanceof AsmScopeEnd && curScope != null) {
            scopeRanges.put(curScope, new int[]{scopeStart, i});
            curScope = null;
            scopeStart = -1;
         } else if(l instanceof AsmLabelDecl decl) {
            String sc = ref.scope != null ? ref.scope : "";
            String n = decl.getName().trim();
            String v = decl.getValue().trim();
            if(v.startsWith(".z ")) v = v.substring(3).trim();
            scopeLabelDecls.computeIfAbsent(sc, k -> new LinkedHashMap<>()).put(n, v);
         }
      }

      boolean modified = false;
      for(Procedure proc : getProgram().getScope().getAllProcedures(true)) {
         if(proc == null || proc.isAReturn()) continue;
         String procName = proc.getLocalName();
         if("main".equals(procName) || "__start".equals(procName)) continue;
         if(proc.getInterruptType() != null) continue;
         if(proc.getCallingConvention() == Procedure.CallingConvention.STACK_CALL) continue;
         if(!SymbolType.BYTE.equals(proc.getReturnType()) && !SymbolType.SBYTE.equals(proc.getReturnType())) continue;
         if(Pass2ConstantIdentification.isAddressOfUsed(proc.getRef(), getProgram())) continue;

         Variable retVar = proc.getLocalVariable("return");
         if(retVar == null || !(retVar.getAllocation() instanceof Registers.RegisterZpMem zpReg)) continue;

         int[] range = scopeRanges.get(procName);
         if(range == null) continue;

         String retAsmName = retVar.getAsmName() != null ? retVar.getAsmName() : "return";
         String qualifiedRetName = procName + "." + retAsmName;
         String retZpHex = String.format("$%02x", zpReg.getZp());
         CanonicalResolver calleeResolver = scopeResolvers.getOrDefault(procName, scopeResolvers.get(""));
         String retCanon = calleeResolver != null ? calleeResolver.resolve(retAsmName) : retZpHex;

         // 1. Verify callee: every RTS must be immediately preceded (without crossing labels/branches) by sta/stx/sty return
         List<Integer> calleeStoreIndices = new ArrayList<>();
         boolean calleeOk = true;
         boolean calleeSetsNZForA = true;
         int rtsCount = 0;
         for(int i = range[0] + 1; i < range[1]; i++) {
            AsmLine l = allLines.get(i).line;
            if(l instanceof AsmInlineKickAsm) {
               calleeOk = false;
               break;
            }
            if(l instanceof AsmInstruction inst && inst.getCpuOpcode() != null) {
               String m = inst.getCpuOpcode().getMnemonic();
               String op = inst.getOperand1();
               // Reject recursive calls or tail-calls to self
               if(("jsr".equals(m) || "jmp".equals(m)) && op != null && procName.equals(op.trim())) {
                  calleeOk = false;
                  break;
               }
               if("rts".equals(m)) {
                  rtsCount++;
                  int prevIdx = findPrevInstruction(allLines, i - 1);
                  if(prevIdx <= range[0]) {
                     calleeOk = false;
                     break;
                  }
                  // Verify no AsmLabel between prevIdx and i (CF-09 multi-path safety)
                  boolean labelBetween = false;
                  for(int k = prevIdx + 1; k < i; k++) {
                     if(allLines.get(k).line instanceof AsmLabel) {
                        labelBetween = true;
                        break;
                     }
                  }
                  if(labelBetween) {
                     calleeOk = false;
                     break;
                  }
                  AsmInstruction prevInst = (AsmInstruction) allLines.get(prevIdx).line;
                  if(prevInst.isNoOptimize() || prevInst.getCpuOpcode() == null) {
                     calleeOk = false;
                     break;
                  }
                  String pm = prevInst.getCpuOpcode().getMnemonic();
                  String pop = prevInst.getOperand1();
                  String popCanon = (pop != null && calleeResolver != null) ? calleeResolver.resolve(pop) : null;
                  boolean matchesRet = pop != null && (pop.equals(retAsmName) || pop.equals(qualifiedRetName)
                        || (popCanon != null && (popCanon.equals(retCanon) || popCanon.equalsIgnoreCase(retZpHex))));
                  if((pm.equals("sta") || pm.equals("stx") || pm.equals("sty")) &&
                     prevInst.getCpuOpcode().getAddressingMode() == CpuAddressingMode.ZP &&
                     matchesRet) {
                     calleeStoreIndices.add(prevIdx);
                     if(pm.equals("sta") && !areFlagsEstablishedForReg(allLines, prevIdx, "A", procName)) {
                        calleeSetsNZForA = false;
                     }
                  } else {
                     calleeOk = false;
                     break;
                  }
               }
            }
         }
         if(!calleeOk || rtsCount == 0) continue;

         // Ensure callee itself does not read or otherwise access retAsmName (or any canonical alias) anywhere else
         for(int i = range[0] + 1; i < range[1]; i++) {
            if(calleeStoreIndices.contains(i)) continue;
            AsmLine l = allLines.get(i).line;
            if(l instanceof AsmInstruction inst && inst.getOperand1() != null) {
               String op = inst.getOperand1().trim();
               String opBase = getBaseVar(op);
               String opCanon = calleeResolver != null ? calleeResolver.resolve(op) : op;
               if(op.equals(retAsmName) || op.equals(qualifiedRetName) ||
                  opBase.equals(retAsmName) || opBase.equals(qualifiedRetName) ||
                  opCanon.equals(retCanon) || opCanon.equalsIgnoreCase(retZpHex)) {
                  calleeOk = false;
                  break;
               }
            }
         }
         if(!calleeOk) continue;

         // Collect per-caller-scope transitive .label aliases pointing to qualifiedRetName (e.g. .label __3 = fn.return)
         Map<String, Set<String>> callerRetAliases = new HashMap<>();
         for(Map.Entry<String, Map<String, String>> scEntry : scopeLabelDecls.entrySet()) {
            String sc = scEntry.getKey();
            if(procName.equals(sc)) continue;
            Map<String, String> decls = scEntry.getValue();
            Set<String> aliases = new HashSet<>();
            aliases.add(qualifiedRetName);
            boolean grown = true;
            while(grown) {
               grown = false;
               for(Map.Entry<String, String> d : decls.entrySet()) {
                  String lhs = d.getKey();
                  String rhsBase = getBaseVar(d.getValue());
                  if(aliases.contains(rhsBase) && aliases.add(lhs)) {
                     grown = true;
                  }
               }
            }
            if(aliases.size() > 1) {
               callerRetAliases.put(sc, aliases);
            }
         }

         // 2. Verify all callers across the entire program (including recursion / tail-calls / aliases / N&Z flags)
         List<Integer> callerLoadIndices = new ArrayList<>();
         boolean callersOk = true;
         int callCount = 0;
         for(int i = 0; i < allLines.size(); i++) {
            if(i >= range[0] && i <= range[1]) continue;
            LineRef ref = allLines.get(i);
            String refScope = ref.scope != null ? ref.scope : "";
            AsmLine l = ref.line;
            if(l instanceof AsmInlineKickAsm inlineAsm) {
               String code = inlineAsm.getKickAsmCode();
               if(code != null && (code.contains(procName) || code.contains(qualifiedRetName))) {
                  callersOk = false;
                  break;
               }
               continue;
            }
            if(l instanceof AsmInstruction inst && inst.getCpuOpcode() != null) {
               String m = inst.getCpuOpcode().getMnemonic();
               String op = inst.getOperand1();
               String opTrim = op != null ? op.trim() : null;
               Set<String> scopeAliases = callerRetAliases.get(refScope);

               if("jmp".equals(m) && opTrim != null && procName.equals(opTrim)) {
                  // Tail-call into procName: caller's callers still expect ZP return ABI -> abort
                  callersOk = false;
                  break;
               }
               if("jsr".equals(m) && opTrim != null && procName.equals(opTrim)) {
                  callCount++;
                  int nextIdx = findNextInstruction(allLines, i + 1);
                  if(nextIdx != -1) {
                     AsmInstruction nextInst = (AsmInstruction) allLines.get(nextIdx).line;
                     String nextOp = nextInst.getOperand1() != null ? nextInst.getOperand1().trim() : null;
                     boolean isDirectRetLoad = nextInst.getCpuOpcode() != null && !nextInst.isNoOptimize() &&
                        "lda".equals(nextInst.getCpuOpcode().getMnemonic()) &&
                        nextInst.getCpuOpcode().getAddressingMode() == CpuAddressingMode.ZP &&
                        nextOp != null && (qualifiedRetName.equals(nextOp) || (scopeAliases != null && scopeAliases.contains(nextOp)));
                     if(isDirectRetLoad) {
                        // Ensure N/Z flags are either established in callee before RTS or dead after the eliminated LDA
                        if(!calleeSetsNZForA && areNZFlagsLiveAfter(allLines, nextIdx, allLines.get(nextIdx).scope, getProgram())) {
                           callersOk = false;
                           break;
                        }
                        callerLoadIndices.add(nextIdx);
                        continue;
                     }
                  }
                  // If not followed by lda.z procName.return, only safe if A is dead after jsr
                  if(!isRegDeadAlongPath("A", allLines, i + 1, new HashSet<>(), refScope, getProgram())) {
                     callersOk = false;
                     break;
                  }
               } else if(opTrim != null) {
                  String opBase = getBaseVar(opTrim);
                  boolean refsReturn = qualifiedRetName.equals(opTrim) || qualifiedRetName.equals(opBase)
                        || (scopeAliases != null && (scopeAliases.contains(opTrim) || scopeAliases.contains(opBase)));
                  if(refsReturn && !callerLoadIndices.contains(i)) {
                     // Any other reference (including via .label alias) to procName.return -> abort
                     callersOk = false;
                     break;
                  }
               }
            }
         }
         if(!callersOk || callCount == 0 || callerLoadIndices.isEmpty()) continue;

         // 3. Apply transformation: callee stores -> removed (or txa/tya), caller loads -> removed
         CpuOpcode txaOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("txa", CpuAddressingMode.NON, false);
         CpuOpcode tyaOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("tya", CpuAddressingMode.NON, false);
         for(int storeIdx : calleeStoreIndices) {
            AsmInstruction storeInst = (AsmInstruction) allLines.get(storeIdx).line;
            String sm = storeInst.getCpuOpcode().getMnemonic();
            if("sta".equals(sm)) {
               tombstoneLine(allLines, storeIdx);
            } else if("stx".equals(sm) && txaOp != null) {
               storeInst.setCpuOpcode(txaOp);
               storeInst.setOperand1(null);
            } else if("sty".equals(sm) && tyaOp != null) {
               storeInst.setCpuOpcode(tyaOp);
               storeInst.setOperand1(null);
            }
         }
         for(int loadIdx : callerLoadIndices) {
            tombstoneLine(allLines, loadIdx);
         }
         proc.setAReturn(true);
         getLog().append("GEN-06: Optimized 8-bit return of " + procName + " to A register (" + callerLoadIndices.size() + " call sites)");
         modified = true;
      }

      return modified;
   }
}
