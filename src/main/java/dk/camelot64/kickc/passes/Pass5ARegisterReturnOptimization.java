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
      List<LineRef> allLines = getSharedAllLines();
      if(allLines.isEmpty()) return false;
      Map<String, CanonicalResolver> scopeResolvers = getSharedScopeResolvers(allLines);

      // Map procedure name -> scope range in allLines, and collect per-scope .label declarations and call/operand indices
      Map<String, int[]> scopeRanges = new LinkedHashMap<>();
      Map<String, Map<String, String>> scopeLabelDecls = new HashMap<>();
      Map<String, List<Integer>> jsrSites = new HashMap<>();
      Map<String, List<Integer>> jmpSites = new HashMap<>();
      List<Integer> inlineKickAsmIndices = new ArrayList<>();
      Map<String, List<Integer>> dotOperandRefs = new HashMap<>();
      Map<String, Map<String, List<Integer>>> scopeOperandRefs = new HashMap<>();
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
         } else if(l instanceof AsmInlineKickAsm) {
            inlineKickAsmIndices.add(i);
         } else if(l instanceof AsmInstruction inst && inst.getCpuOpcode() != null) {
            String m = inst.getCpuOpcode().getMnemonic();
            String op = inst.getOperand1();
            if(op != null) {
               String opTrim = op.trim();
               if("jsr".equals(m)) {
                  jsrSites.computeIfAbsent(opTrim, k -> new ArrayList<>()).add(i);
               } else if("jmp".equals(m)) {
                  jmpSites.computeIfAbsent(opTrim, k -> new ArrayList<>()).add(i);
               }
               String opBase = getBaseVar(opTrim);
               String sc = ref.scope != null ? ref.scope : "";
               Map<String, List<Integer>> scRefs = scopeOperandRefs.computeIfAbsent(sc, k -> new HashMap<>());
               scRefs.computeIfAbsent(opBase, k -> new ArrayList<>()).add(i);
               if(!opTrim.equals(opBase)) {
                  scRefs.computeIfAbsent(opTrim, k -> new ArrayList<>()).add(i);
               }
               if(opBase.indexOf('.') != -1) {
                  dotOperandRefs.computeIfAbsent(opBase, k -> new ArrayList<>()).add(i);
               }
               if(!opTrim.equals(opBase) && opTrim.indexOf('.') != -1) {
                  dotOperandRefs.computeIfAbsent(opTrim, k -> new ArrayList<>()).add(i);
               }
            }
         }
      }

      // Build reverse .label index per scope for O(1) caller return alias lookup
      Map<String, Map<String, List<String>>> scopeReverseDecls = new HashMap<>();
      Map<String, Set<String>> scopesReferencingRhs = new HashMap<>();
      for(Map.Entry<String, Map<String, String>> scEntry : scopeLabelDecls.entrySet()) {
         String sc = scEntry.getKey();
         Map<String, List<String>> rev = new HashMap<>();
         for(Map.Entry<String, String> d : scEntry.getValue().entrySet()) {
            String lhs = d.getKey();
            String rhsBase = getBaseVar(d.getValue());
            rev.computeIfAbsent(rhsBase, k -> new ArrayList<>()).add(lhs);
            scopesReferencingRhs.computeIfAbsent(rhsBase, k -> new LinkedHashSet<>()).add(sc);
         }
         scopeReverseDecls.put(sc, rev);
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
         Set<String> candidateAliasScopes = scopesReferencingRhs.get(qualifiedRetName);
         if(candidateAliasScopes != null) {
            for(String sc : candidateAliasScopes) {
               if(procName.equals(sc)) continue;
               Map<String, List<String>> rev = scopeReverseDecls.get(sc);
               if(rev == null) continue;
               Set<String> aliases = new HashSet<>();
               aliases.add(qualifiedRetName);
               ArrayDeque<String> queue = new ArrayDeque<>();
               queue.add(qualifiedRetName);
               while(!queue.isEmpty()) {
                  String cur = queue.poll();
                  List<String> lhss = rev.get(cur);
                  if(lhss != null) {
                     for(String lhs : lhss) {
                        if(aliases.add(lhs)) {
                           queue.add(lhs);
                        }
                     }
                  }
               }
               if(aliases.size() > 1) {
                  callerRetAliases.put(sc, aliases);
               }
            }
         }

         // 2. Verify all callers across the entire program using pre-indexed sites (recursion / tail-calls / aliases / N&Z flags)
         List<Integer> callerLoadIndices = new ArrayList<>();
         Set<Integer> callerLoadSet = new HashSet<>();
         boolean callersOk = true;
         int callCount = 0;

         for(int i : inlineKickAsmIndices) {
            if(i >= range[0] && i <= range[1]) continue;
            if(allLines.get(i).line instanceof AsmInlineKickAsm inlineAsm) {
               String code = inlineAsm.getKickAsmCode();
               if(code != null && (code.contains(procName) || code.contains(qualifiedRetName))) {
                  callersOk = false;
                  break;
               }
            }
         }

         if(callersOk) {
            List<Integer> procJmps = jmpSites.get(procName);
            if(procJmps != null) {
               for(int i : procJmps) {
                  if(i >= range[0] && i <= range[1]) continue;
                  if(allLines.get(i).line instanceof AsmInstruction) {
                     // Tail-call into procName: caller's callers still expect ZP return ABI -> abort
                     callersOk = false;
                     break;
                  }
               }
            }
         }

         if(callersOk) {
            List<Integer> procJsrs = jsrSites.get(procName);
            if(procJsrs != null) {
               for(int i : procJsrs) {
                  if(i >= range[0] && i <= range[1]) continue;
                  LineRef ref = allLines.get(i);
                  if(!(ref.line instanceof AsmInstruction)) continue;
                  String refScope = ref.scope != null ? ref.scope : "";
                  Set<String> scopeAliases = callerRetAliases.get(refScope);
                  callCount++;
                  CanonicalResolver callerResolver = scopeResolvers.getOrDefault(refScope, scopeResolvers.get(""));
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
                        // Ensure retZpHex is not later read in caller via a memory-coalesced ZP variable
                        if(!isZpAddrDeadAlongPath(allLines, nextIdx + 1, zpReg.getZp(), retZpHex, retCanon, callerResolver, refScope, new HashSet<>())) {
                           callersOk = false;
                           break;
                        }
                        callerLoadIndices.add(nextIdx);
                        callerLoadSet.add(nextIdx);
                        continue;
                     }
                  }
                  // If not followed by lda.z procName.return, only safe if A is dead AND retZpHex is not read via a coalesced ZP variable
                  if(!isRegDeadAlongPath("A", allLines, i + 1, new HashSet<>(), refScope, getProgram())
                        || !isZpAddrDeadAlongPath(allLines, i + 1, zpReg.getZp(), retZpHex, retCanon, callerResolver, refScope, new HashSet<>())) {
                     callersOk = false;
                     break;
                  }
               }
            }
         }
         if(!callersOk || callCount == 0 || callerLoadIndices.isEmpty()) continue;

         // Verify no other instructions reference procName.return (directly or via .label alias)
         List<Integer> directRetRefs = dotOperandRefs.get(qualifiedRetName);
         if(directRetRefs != null) {
            for(int idx : directRetRefs) {
               if(idx >= range[0] && idx <= range[1]) continue;
               if(callerLoadSet.contains(idx)) continue;
               AsmLine l = allLines.get(idx).line;
               if(!(l instanceof AsmInstruction inst) || inst.getCpuOpcode() == null) continue;
               String m = inst.getCpuOpcode().getMnemonic();
               String opTrim = inst.getOperand1() != null ? inst.getOperand1().trim() : null;
               if(("jmp".equals(m) || "jsr".equals(m)) && procName.equals(opTrim)) continue;
               callersOk = false;
               break;
            }
         }
         if(callersOk && !callerRetAliases.isEmpty()) {
            for(Map.Entry<String, Set<String>> aliasEntry : callerRetAliases.entrySet()) {
               Map<String, List<Integer>> scRefs = scopeOperandRefs.get(aliasEntry.getKey());
               if(scRefs == null) continue;
               for(String alias : aliasEntry.getValue()) {
                  if(qualifiedRetName.equals(alias)) continue;
                  List<Integer> aliasRefs = scRefs.get(alias);
                  if(aliasRefs == null) continue;
                  for(int idx : aliasRefs) {
                     if(idx >= range[0] && idx <= range[1]) continue;
                     if(callerLoadSet.contains(idx)) continue;
                     AsmLine l = allLines.get(idx).line;
                     if(!(l instanceof AsmInstruction inst) || inst.getCpuOpcode() == null) continue;
                     String m = inst.getCpuOpcode().getMnemonic();
                     String opTrim = inst.getOperand1() != null ? inst.getOperand1().trim() : null;
                     if(("jmp".equals(m) || "jsr".equals(m)) && procName.equals(opTrim)) continue;
                     callersOk = false;
                     break;
                  }
                  if(!callersOk) break;
               }
               if(!callersOk) break;
            }
         }
         if(!callersOk) continue;

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
            String callerScope = allLines.get(loadIdx).scope;
            markScopeDirty(callerScope != null ? callerScope : "");
            tombstoneLine(allLines, loadIdx);
         }
         markScopeDirty(procName);
         proc.setAReturn(true);
         getLog().append("GEN-06: Optimized 8-bit return of " + procName + " to A register (" + callerLoadIndices.size() + " call sites)");
         modified = true;
      }

      if(modified) {
         invalidateRoundStructure();
      }
      return modified;
   }

   /**
    * Verifies that the zero-page byte at retZp (retZpHex / retCanon) is not read along any
    * execution path starting at startIdx within currentScope before being overwritten.
    * Guards against Pass4MemoryCoalesce placing a caller local variable, parameter, or return
    * variable onto the exact same ZP address as the callee's return slot.
    */
   private boolean isZpAddrDeadAlongPath(
         List<LineRef> allLines,
         int startIdx,
         int retZp,
         String retZpHex,
         String retCanon,
         CanonicalResolver resolver,
         String currentScope,
         Set<Integer> visited) {
      for(int m = startIdx; m < allLines.size(); m++) {
         if(!visited.add(m)) return true;
         LineRef ref = allLines.get(m);
         if(currentScope != null && !currentScope.isEmpty() && !currentScope.equals(ref.scope)) {
            return true;
         }
         AsmLine line = ref.line;
         if(line instanceof AsmScopeEnd) return true;
         if(line instanceof AsmInlineKickAsm) return false;
         if(!(line instanceof AsmInstruction inst) || inst.getCpuOpcode() == null) continue;

         String mnem = inst.getCpuOpcode().getMnemonic();
         CpuAddressingMode mode = inst.getCpuOpcode().getAddressingMode();
         String op = inst.getOperand1();

         if("rts".equals(mnem) || "rti".equals(mnem)) {
            if("rts".equals(mnem) && currentScope != null && !currentScope.isEmpty() && getProgram().getScope() != null) {
               var sym = getProgram().getScope().findSymbol(currentScope);
               if(sym instanceof Procedure callerProc) {
                  if(SymbolType.VOID.equals(callerProc.getReturnType())) return true;
                  Variable callerRet = callerProc.getLocalVariable("return");
                  if(callerRet == null || !(callerRet.getAllocation() instanceof Registers.RegisterZpMem callerZp)) return true;
                  int cZp = callerZp.getZp();
                  int cSize = Math.max(1, callerRet.getType().getSizeBytes());
                  return retZp < cZp || retZp >= cZp + cSize;
               }
            }
            return false;
         }
         if("jmp".equals(mnem)) {
            if(mode == CpuAddressingMode.ABS && op != null) {
               int targetIdx = findLabelIndex(allLines, op.trim(), m, currentScope);
               if(targetIdx != -1) {
                  return isZpAddrDeadAlongPath(allLines, targetIdx + 1, retZp, retZpHex, retCanon, resolver, currentScope, visited);
               }
            }
            return false;
         }
         if(op != null && (mode == CpuAddressingMode.ZP || mode == CpuAddressingMode.ZPX || mode == CpuAddressingMode.ZPY
               || mode == CpuAddressingMode.IZX || mode == CpuAddressingMode.IZY || mode == CpuAddressingMode.ABS)) {
            String opTrim = op.trim();
            String opCanon = resolver != null ? resolver.resolve(opTrim) : opTrim;
            boolean matches = opCanon.equalsIgnoreCase(retZpHex) || opCanon.equals(retCanon);
            if(!matches && (mode == CpuAddressingMode.IZX || mode == CpuAddressingMode.IZY) && resolver != null) {
               String hiCanon = resolver.resolve(opTrim + "+1");
               if(hiCanon.equalsIgnoreCase(retZpHex) || hiCanon.equals(retCanon)) {
                  matches = true;
               }
            }
            if(matches) {
               if(mode == CpuAddressingMode.ZP && ("sta".equals(mnem) || "stx".equals(mnem) || "sty".equals(mnem) || "stz".equals(mnem) || "sax".equals(mnem))) {
                  return true;
               }
               return false;
            }
         }
         if("jsr".equals(mnem)) {
            if(op != null && getProgram().getScope() != null) {
               var sym = getProgram().getScope().findSymbol(op.trim());
               if(sym instanceof Procedure targetProc) {
                  if(targetProc.getParameterNames() != null) {
                     for(Variable p : targetProc.getParameters()) {
                        if(p.getAllocation() instanceof Registers.RegisterZpMem pZp) {
                           int zp = pZp.getZp();
                           int sz = Math.max(1, p.getType().getSizeBytes());
                           if(retZp >= zp && retZp < zp + sz) {
                              return false;
                           }
                        }
                     }
                  }
                  Variable tRet = targetProc.getLocalVariable("return");
                  if(tRet != null && tRet.getAllocation() instanceof Registers.RegisterZpMem tRetZp) {
                     int zp = tRetZp.getZp();
                     int sz = Math.max(1, tRet.getType().getSizeBytes());
                     if(retZp >= zp && retZp < zp + sz) {
                        return true;
                     }
                  }
                  continue;
               }
            }
            return false;
         }
         if(mnem.startsWith("b")) {
            if(op == null) return false;
            int targetIdx = findLabelIndex(allLines, op.trim(), m, currentScope);
            if(targetIdx == -1) return false;
            if(!isZpAddrDeadAlongPath(allLines, targetIdx + 1, retZp, retZpHex, retCanon, resolver, currentScope, visited)) {
               return false;
            }
         }
      }
      return false;
   }
}
