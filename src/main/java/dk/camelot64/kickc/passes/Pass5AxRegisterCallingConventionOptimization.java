package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Variable;

import java.util.*;

/**
 * 16-Bit Hardware Register Pair AX for Calling Conventions (__ax / Auto-AX ABI).
 *
 * In 6502 assembly, passing and returning 16-bit values (words, ints, pointers)
 * in the AX register pair (A = low byte, X = high byte) eliminates Zero-Page and memory
 * allocation for function returns and parameters.
 */
public class Pass5AxRegisterCallingConventionOptimization extends Pass5AsmOptimization {

   private boolean performed = false;

   public Pass5AxRegisterCallingConventionOptimization(Program program) {
      super(program);
   }

   private List<LineRef> buildAllLines() {
      List<LineRef> allLines = new ArrayList<>();
      int lineIdx = 0;
      String currentScope = "";
      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         String chunkScope = chunk.getScopeLabel();
         if(chunkScope != null && !chunkScope.isEmpty()) currentScope = chunkScope;
         for(AsmLine line : chunk.getLines()) {
            if(line instanceof AsmScopeBegin sb) {
               currentScope = sb.getLabel() != null ? sb.getLabel() : "";
            }
            allLines.add(new LineRef(lineIdx++, chunk, line, currentScope));
            if(line instanceof AsmScopeEnd) {
               currentScope = "";
            }
         }
      }
      return allLines;
   }

   private static int prevInstIdx(List<LineRef> allLines, int startIdx) {
      for(int i = startIdx; i >= 0; i--) {
         AsmLine line = allLines.get(i).line;
         if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl) {
            continue;
         }
         if(line instanceof AsmInstruction) {
            return i;
         }
         // Stop at AsmLabel, AsmScopeBegin, AsmScopeEnd, AsmInlineKickAsm, etc. (CF-09)
         break;
      }
      return -1;
   }

   private static int nextInstIdx(List<LineRef> allLines, int startIdx) {
      for(int i = startIdx; i < allLines.size(); i++) {
         AsmLine line = allLines.get(i).line;
         if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl) {
            continue;
         }
         if(line instanceof AsmInstruction) {
            return i;
         }
         // Stop at AsmLabel, AsmScopeBegin, AsmScopeEnd, AsmInlineKickAsm, etc. (CF-09)
         break;
      }
      return -1;
   }

   private static boolean hasEarlierInstInScope(List<LineRef> allLines, int idx, String scope) {
      for(int i = idx - 1; i >= 0; i--) {
         LineRef ref = allLines.get(i);
         if(ref.scope == null || !scope.equals(ref.scope)) break;
         if(ref.line instanceof AsmScopeBegin) break;
         if(ref.line instanceof AsmInstruction) return true;
      }
      return false;
   }

   private static String stripPlus1(String op) {
      if(op == null) return null;
      String t = op.trim();
      if(t.endsWith("+1")) {
         return t.substring(0, t.length() - 2).trim();
      }
      return null;
   }

   private static boolean matchesVarLo(String operand, String varName, String varAddr, Map<String, String> aliases) {
      if(operand == null) return false;
      String t = operand.trim();
      if(varName != null && t.equals(varName)) return true;
      if(varAddr != null && t.equals(varAddr)) return true;
      if(aliases != null) {
         String resolved = aliases.get(t);
         if(resolved != null) {
            if(varName != null && resolved.equals(varName)) return true;
            if(varAddr != null && resolved.equals(varAddr)) return true;
         }
      }
      return false;
   }

   private static boolean matchesVarHi(String operand, String varName, String varAddr, Map<String, String> aliases) {
      if(operand == null) return false;
      String stripped = stripPlus1(operand);
      if(stripped != null) {
         return matchesVarLo(stripped, varName, varAddr, aliases);
      }
      try {
         if(varAddr != null) {
            int addr = Integer.parseInt(varAddr);
            int opAddr = Integer.parseInt(operand.trim());
            if(opAddr == addr + 1) return true;
         }
      } catch(NumberFormatException ignored) {}
      return false;
   }

   private static Long parseConstValue(String val) {
      if(val == null) return null;
      String t = val.trim();
      try {
         if(t.startsWith("$")) {
            return Long.parseLong(t.substring(1), 16);
         } else if(t.startsWith("0x") || t.startsWith("0X")) {
            return Long.parseLong(t.substring(2), 16);
         } else if(t.startsWith("%")) {
            return Long.parseLong(t.substring(1), 2);
         } else {
            return Long.parseLong(t);
         }
      } catch(NumberFormatException e) {
         return null;
      }
   }

   private static boolean readsRegisterX(AsmInstruction inst) {
      if(inst.getCpuOpcode() == null) return true;
      CpuAddressingMode mode = inst.getCpuOpcode().getAddressingMode();
      if(mode != null && mode.name().contains("X")) {
         return true;
      }
      String mnem = inst.getCpuOpcode().getMnemonic().toLowerCase();
      return mnem.equals("txa") || mnem.equals("txs") || mnem.equals("stx") ||
             mnem.equals("cpx") || mnem.equals("inx") || mnem.equals("dex") ||
             mnem.equals("axs") || mnem.equals("sax") || mnem.equals("phx") ||
             mnem.equals("shx") || mnem.equals("sxa") || mnem.equals("tas") || mnem.equals("xas");
   }

   private static boolean overwritesRegisterX(AsmInstruction inst) {
      if(inst.getCpuOpcode() == null) return false;
      String mnem = inst.getCpuOpcode().getMnemonic().toLowerCase();
      return mnem.equals("ldx") || mnem.equals("tax") || mnem.equals("tsx") ||
             mnem.equals("lax") || mnem.equals("plx");
   }

   private static boolean readsRegisterA(AsmInstruction inst) {
      if(inst.getCpuOpcode() == null) return true;
      String mnem = inst.getCpuOpcode().getMnemonic().toLowerCase();
      CpuAddressingMode mode = inst.getCpuOpcode().getAddressingMode();
      if(mnem.equals("sta") || mnem.equals("sax") || mnem.equals("pha") ||
         mnem.equals("tax") || mnem.equals("tay") || mnem.equals("cmp") ||
         mnem.equals("adc") || mnem.equals("sbc") || mnem.equals("and") ||
         mnem.equals("ora") || mnem.equals("eor") || mnem.equals("bit") ||
         mnem.equals("dcp") || mnem.equals("isc") || mnem.equals("slo") ||
         mnem.equals("rla") || mnem.equals("sre") || mnem.equals("rra") ||
         mnem.equals("alr") || mnem.equals("arr") || mnem.equals("anc") ||
         mnem.equals("axs") || mnem.equals("sha") || mnem.equals("ahx") ||
         mnem.equals("tas") || mnem.equals("xas") || mnem.equals("shy")) {
         return true;
      }
      return (mnem.equals("asl") || mnem.equals("lsr") || mnem.equals("rol") || mnem.equals("ror")) &&
             (CpuAddressingMode.NON.equals(mode) || "a".equalsIgnoreCase(inst.getOperand1()));
   }

   private static boolean overwritesRegisterA(AsmInstruction inst) {
      if(inst.getCpuOpcode() == null) return false;
      String mnem = inst.getCpuOpcode().getMnemonic().toLowerCase();
      return mnem.equals("lda") || mnem.equals("pla") || mnem.equals("lax") ||
             mnem.equals("txa") || mnem.equals("tya");
   }

   private static final class WalkResult {
      boolean readsReg;
      boolean reachesRts;
   }

   private static final class CallEdge {
      final String caller;
      final String callee;
      final boolean checkX;

      CallEdge(String caller, String callee, boolean checkX) {
         this.caller = caller;
         this.callee = callee;
         this.checkX = checkX;
      }
   }

   /**
    * Walks all reachable intra-procedural CFG paths in {@code scope} starting at {@code startIdx}
    * for register {@code checkX ? "X" : "A"}.
    * Stops exploring any path that unconditionally overwrites the register before reading it.
    * Reports whether any path reads the register before overwriting it, and whether any path
    * reaches {@code rts} (or a preserving tail-call) without overwriting it.
    */
   private static WalkResult analyzeScopeFrom(
         List<LineRef> allLines,
         String scope,
         int startIdx,
         boolean checkX,
         Map<String, Integer> scopeStartIdx,
         Map<String, Procedure> procByName,
         Set<String> readsOnEntry,
         Set<String> mayPreserve,
         int[] visitStamp,
         int stamp) {
      WalkResult res = new WalkResult();
      ArrayDeque<Integer> worklist = new ArrayDeque<>();
      if(startIdx >= 0 && startIdx < allLines.size() && visitStamp[startIdx] != stamp) {
         visitStamp[startIdx] = stamp;
         worklist.add(startIdx);
      }

      while(!worklist.isEmpty()) {
         int idx = worklist.poll();
         if(idx < 0 || idx >= allLines.size()) {
            res.reachesRts = true;
            continue;
         }
         LineRef ref = allLines.get(idx);
         if(!scope.equals(ref.scope) || ref.line instanceof AsmScopeEnd) {
            res.reachesRts = true;
            continue;
         }
         if(ref.line instanceof AsmComment || ref.line instanceof AsmConstant ||
            ref.line instanceof AsmLabelDecl || ref.line instanceof AsmLabel) {
            int next = idx + 1;
            if(next < allLines.size() && visitStamp[next] != stamp) {
               visitStamp[next] = stamp;
               worklist.add(next);
            } else if(next >= allLines.size()) {
               res.reachesRts = true;
            }
            continue;
         }
         if(ref.line instanceof AsmInlineKickAsm) {
            res.readsReg = true;
            res.reachesRts = true;
            continue;
         }
         if(ref.line instanceof AsmInstruction inst) {
            CpuOpcode op = inst.getCpuOpcode();
            if(op == null) {
               res.readsReg = true;
               res.reachesRts = true;
               continue;
            }
            String mnem = op.getMnemonic().toLowerCase();
            CpuAddressingMode mode = op.getAddressingMode();

            if(checkX ? readsRegisterX(inst) : readsRegisterA(inst)) {
               res.readsReg = true;
            }
            if(checkX ? overwritesRegisterX(inst) : overwritesRegisterA(inst)) {
               // Register killed along this path
               continue;
            }

            if("rts".equals(mnem)) {
               res.reachesRts = true;
               continue;
            }
            if("rti".equals(mnem)) {
               Procedure p = procByName.get(scope);
               if(p != null && p.getInterruptType() != null) {
                  String it = p.getInterruptType().toLowerCase();
                  if(!it.contains("all") && !it.contains("clobber")) {
                     res.readsReg = true;
                  }
               }
               continue;
            }
            if("jmp".equals(mnem)) {
               if(mode == CpuAddressingMode.IND || mode == CpuAddressingMode.IAX) {
                  res.readsReg = true;
                  res.reachesRts = true;
                  continue;
               }
               String target = inst.getOperand1() != null ? inst.getOperand1().trim() : null;
               if(target != null) {
                  int targetIdx = Pass5AsmOptimization.findLabelIndex(allLines, target, idx, scope);
                  if(targetIdx != -1) {
                     int next = targetIdx + 1;
                     if(next < allLines.size() && visitStamp[next] != stamp) {
                        visitStamp[next] = stamp;
                        worklist.add(next);
                     }
                     continue;
                  }
                  if(scopeStartIdx.containsKey(target) && !target.equals(scope)) {
                     if(readsOnEntry.contains(target)) res.readsReg = true;
                     if(mayPreserve.contains(target)) res.reachesRts = true;
                     continue;
                  }
               }
               res.readsReg = true;
               res.reachesRts = true;
               continue;
            }
            if("jsr".equals(mnem)) {
               String target = inst.getOperand1() != null ? inst.getOperand1().trim() : null;
               if(target == null || !scopeStartIdx.containsKey(target)) {
                  res.readsReg = true;
                  int next = idx + 1;
                  if(next < allLines.size() && visitStamp[next] != stamp) {
                     visitStamp[next] = stamp;
                     worklist.add(next);
                  }
               } else {
                  if(readsOnEntry.contains(target)) {
                     res.readsReg = true;
                  }
                  if(mayPreserve.contains(target)) {
                     int next = idx + 1;
                     if(next < allLines.size() && visitStamp[next] != stamp) {
                        visitStamp[next] = stamp;
                        worklist.add(next);
                     }
                  }
               }
               continue;
            }
            if(mnem.startsWith("b")) {
               String target = inst.getOperandJumpTarget();
               int targetIdx = (target != null) ? Pass5AsmOptimization.findLabelIndex(allLines, target.trim(), idx, scope) : -1;
               if(targetIdx == -1) {
                  res.readsReg = true;
                  res.reachesRts = true;
               } else {
                  int branchDest = targetIdx + 1;
                  if(branchDest < allLines.size() && visitStamp[branchDest] != stamp) {
                     visitStamp[branchDest] = stamp;
                     worklist.add(branchDest);
                  }
               }
               if(!"bra".equals(mnem)) {
                  int next = idx + 1;
                  if(next < allLines.size() && visitStamp[next] != stamp) {
                     visitStamp[next] = stamp;
                     worklist.add(next);
                  }
               }
               continue;
            }

            int next = idx + 1;
            if(next < allLines.size() && visitStamp[next] != stamp) {
               visitStamp[next] = stamp;
               worklist.add(next);
            } else if(next >= allLines.size()) {
               res.reachesRts = true;
            }
         }
      }
      return res;
   }

   /**
    * Computes the set of procedures for which register X or A may be live upon return
    * (either directly at a call site or transitively across callers / tail-callers).
    */
   private static void computeLiveOnReturnSets(
         List<LineRef> allLines,
         Map<String, Integer> scopeStartIdx,
         Map<String, Procedure> procByName,
         Set<String> liveXOnReturn,
         Set<String> liveAOnReturn) {
      int[] visitStamp = new int[allLines.size()];
      int stamp = 0;

      // 1. Fixpoint for readsOnEntry & mayPreserve per register (X and A)
      Set<String> readsXOnEntry = new HashSet<>();
      Set<String> mayPreserveX = new HashSet<>();
      Set<String> readsAOnEntry = new HashSet<>();
      Set<String> mayPreserveA = new HashSet<>();

      for(boolean checkX : new boolean[]{true, false}) {
         Set<String> readsOnEntry = checkX ? readsXOnEntry : readsAOnEntry;
         Set<String> mayPreserve = checkX ? mayPreserveX : mayPreserveA;
         boolean changed = true;
         while(changed) {
            changed = false;
            for(Map.Entry<String, Integer> entry : scopeStartIdx.entrySet()) {
               String proc = entry.getKey();
               WalkResult wr = analyzeScopeFrom(
                     allLines, proc, entry.getValue(), checkX,
                     scopeStartIdx, procByName, readsOnEntry, mayPreserve,
                     visitStamp, ++stamp);
               if(wr.readsReg && readsOnEntry.add(proc)) {
                  changed = true;
               }
               if(wr.reachesRts && mayPreserve.add(proc)) {
                  changed = true;
               }
            }
         }
      }

      // 2. Collect call-site liveness and transitive caller->callee edges
      List<CallEdge> edges = new ArrayList<>();
      for(int i = 0; i < allLines.size(); i++) {
         LineRef ref = allLines.get(i);
         if(ref.line instanceof AsmInlineKickAsm inlineAsm) {
            String code = inlineAsm.getKickAsmCode();
            if(code != null) {
               for(String procName : scopeStartIdx.keySet()) {
                  if(!procName.equals(ref.scope) && code.contains(procName)) {
                     liveXOnReturn.add(procName);
                     liveAOnReturn.add(procName);
                  }
               }
            }
            continue;
         }
         if(!(ref.line instanceof AsmInstruction inst) || inst.getCpuOpcode() == null) {
            continue;
         }
         String mnem = inst.getCpuOpcode().getMnemonic().toLowerCase();
         String target = inst.getOperand1() != null ? inst.getOperand1().trim() : null;
         if(target == null || !scopeStartIdx.containsKey(target)) {
            continue;
         }
         String callerScope = ref.scope != null ? ref.scope : "";
         if("jsr".equals(mnem)) {
            for(boolean checkX : new boolean[]{true, false}) {
               Set<String> readsOnEntry = checkX ? readsXOnEntry : readsAOnEntry;
               Set<String> mayPreserve = checkX ? mayPreserveX : mayPreserveA;
               Set<String> liveSet = checkX ? liveXOnReturn : liveAOnReturn;
               WalkResult wr = analyzeScopeFrom(
                     allLines, callerScope, i + 1, checkX,
                     scopeStartIdx, procByName, readsOnEntry, mayPreserve,
                     visitStamp, ++stamp);
               if(wr.readsReg) {
                  liveSet.add(target);
               }
               if(wr.reachesRts) {
                  if(callerScope.isEmpty()) {
                     // Top-level code outside any named procedure scope: conservatively treat as live
                     liveSet.add(target);
                  } else {
                     edges.add(new CallEdge(callerScope, target, checkX));
                  }
               }
            }
         } else if("jmp".equals(mnem) && !target.equals(callerScope)) {
            if(callerScope.isEmpty()) {
               liveXOnReturn.add(target);
               liveAOnReturn.add(target);
            } else {
               edges.add(new CallEdge(callerScope, target, true));
               edges.add(new CallEdge(callerScope, target, false));
            }
         }
      }

      // 3. Transitive closure along caller->callee edges
      boolean changed = true;
      while(changed) {
         changed = false;
         for(CallEdge edge : edges) {
            Set<String> liveSet = edge.checkX ? liveXOnReturn : liveAOnReturn;
            if(liveSet.contains(edge.caller) && liveSet.add(edge.callee)) {
               changed = true;
            }
         }
      }
   }

   private static void checkQualReturnRef(String target, int lineIdx, String refScope,
                                          Set<String> checkProcs,
                                          Map<String, Set<Integer>> handledCallerReadIndices,
                                          Set<String> unsafeAxReturnProcs) {
      if(target != null && target.endsWith(".return")) {
         String candProc = target.substring(0, target.length() - 7); // ".return".length() == 7
         if(checkProcs.contains(candProc) && !candProc.equals(refScope)) {
            Set<Integer> handled = handledCallerReadIndices.getOrDefault(candProc, Collections.emptySet());
            if(!handled.contains(lineIdx)) {
               unsafeAxReturnProcs.add(candProc);
               checkProcs.remove(candProc);
            }
         }
      }
   }

   @Override
   public boolean optimize() {
      if(performed) {
         return false;
      }
      performed = true;

      boolean overallModified = false;
      boolean passModified = true;

      CpuOpcode taxOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("tax", CpuAddressingMode.NON, false);
      CpuOpcode ldxImm = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("ldx", CpuAddressingMode.IMM, false);
      CpuOpcode ldaImm = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lda", CpuAddressingMode.IMM, false);

      List<LineRef> initialLines = buildAllLines();

      // Scope-level label declarations / aliases and scope entry indices (computed once before mutation)
      Map<String, Map<String, String>> scopeAliases = new HashMap<>();
      Map<String, Integer> scopeStartIdx = new LinkedHashMap<>();
      for(int i = 0; i < initialLines.size(); i++) {
         LineRef ref = initialLines.get(i);
         if(ref.line instanceof AsmScopeBegin sb && sb.getLabel() != null && !sb.getLabel().isEmpty()) {
            scopeStartIdx.putIfAbsent(sb.getLabel(), i + 1);
         } else if(ref.line instanceof AsmLabelDecl decl && ref.scope != null && !ref.scope.isEmpty()) {
            scopeAliases.computeIfAbsent(ref.scope, k -> new HashMap<>()).put(decl.getName(), decl.getValue());
         }
      }

      // Collect procedures eligible for AX return and AX parameter
      Set<String> axReturnProcs = new HashSet<>();
      Map<String, Procedure> procByName = new HashMap<>();
      Map<String, String> procReturnVarAddr = new HashMap<>();
      Set<String> axParamProcs = new HashSet<>();
      Map<String, String> procParamVarName = new HashMap<>();
      Map<String, String> procParamVarAddr = new HashMap<>();

      Scope rootScope = getProgram().getScope();
      for(Procedure proc : rootScope.getAllProcedures(true)) {
         String name = proc.getLocalName();
         procByName.put(name, proc);
         if(proc.getInterruptType() != null || proc.isDeclaredIntrinsic()) {
            continue;
         }
         if(Procedure.CallingConvention.STACK_CALL.equals(proc.getCallingConvention())) {
            continue;
         }
         // CF-09: Exclude procedures whose address is taken (function pointer calls cannot be patched)
         if(!proc.isAxConvention() && Pass2ConstantIdentification.isAddressOfUsed(proc.getRef(), getProgram())) {
            continue;
         }

         boolean returns16 = proc.getReturnType() != null && proc.getReturnType().getSizeBytes() == 2 && !(proc.getReturnType() instanceof dk.camelot64.kickc.model.types.SymbolTypeStruct);
         if(proc.isAxConvention() || returns16) {
            Map<String, String> aliases = scopeAliases.get(name);
            if(aliases != null && aliases.containsKey("return")) {
               axReturnProcs.add(name);
               procReturnVarAddr.put(name, aliases.get("return"));
            } else if(proc.isAxConvention()) {
               axReturnProcs.add(name);
            }
         }

         if(proc.isAxConvention() && proc.getParameters().size() == 1) {
            Variable param0 = proc.getParameters().get(0);
            if(param0.getType() != null && param0.getType().getSizeBytes() == 2 && !(param0.getType() instanceof dk.camelot64.kickc.model.types.SymbolTypeStruct)) {
               axParamProcs.add(name);
               String pName = param0.getLocalName();
               procParamVarName.put(name, pName);
               Map<String, String> aliases = scopeAliases.get(name);
               if(aliases != null && aliases.containsKey(pName)) {
                  procParamVarAddr.put(name, aliases.get(pName));
               }
            }
         }
      }

      // -------------------------------------------------------------
      // Interprocedural Call-Site Liveness & Caller Compatibility Validation (CF-09)
      // Computed ONCE before any Step 1/2/3/4 mutation so all callers & callees are validated consistently.
      // -------------------------------------------------------------
      Set<String> liveXOnReturn = new HashSet<>();
      Set<String> liveAOnReturn = new HashSet<>();
      computeLiveOnReturnSets(initialLines, scopeStartIdx, procByName, liveXOnReturn, liveAOnReturn);

      Set<String> unsafeAxReturnProcs = new HashSet<>();
      for(String candProc : axReturnProcs) {
         if(liveXOnReturn.contains(candProc) || liveAOnReturn.contains(candProc)) {
            unsafeAxReturnProcs.add(candProc);
         }
         Procedure p = procByName.get(candProc);
         if(p != null && Pass2ConstantIdentification.isAddressOfUsed(p.getRef(), getProgram())) {
            unsafeAxReturnProcs.add(candProc);
         }
      }
      for(String paramProc : new ArrayList<>(axParamProcs)) {
         if(liveXOnReturn.contains(paramProc) || liveAOnReturn.contains(paramProc)) {
            axParamProcs.remove(paramProc);
         }
      }

      // Collect all call-site instruction indices that Step 4 knows how to patch, and reject tail-call JMPs
      Map<String, Set<Integer>> handledCallerReadIndices = new HashMap<>();
      for(int i = 0; i < initialLines.size(); i++) {
         LineRef ref = initialLines.get(i);
         if(ref.line instanceof AsmInstruction inst && inst.getCpuOpcode() != null) {
            String mn = inst.getCpuOpcode().getMnemonic().toLowerCase();
            String target = inst.getOperand1();
            if("jmp".equals(mn) && target != null) {
               String targetProc = target.trim();
               if(axReturnProcs.contains(targetProc) && !targetProc.equals(ref.scope)) {
                  Procedure p = procByName.get(targetProc);
                  if(p == null || !p.isAxConvention()) {
                     // Tail-call JMP from another scope into auto-AX candidate -> unsafe
                     unsafeAxReturnProcs.add(targetProc);
                  }
               }
            } else if("jsr".equals(mn) && target != null) {
               String targetProc = target.trim();
               if(axReturnProcs.contains(targetProc) && !unsafeAxReturnProcs.contains(targetProc) && ref.scope != null && !ref.scope.isEmpty()) {
                  String retAddr = procReturnVarAddr.get(targetProc);
                  Map<String, String> callerAliases = scopeAliases.get(ref.scope);
                  int idx1 = nextInstIdx(initialLines, i + 1);
                  int idx2 = (idx1 >= 0) ? nextInstIdx(initialLines, idx1 + 1) : -1;
                  int idx3 = (idx2 >= 0) ? nextInstIdx(initialLines, idx2 + 1) : -1;
                  int idx4 = (idx3 >= 0) ? nextInstIdx(initialLines, idx3 + 1) : -1;
                  Set<Integer> handled = handledCallerReadIndices.computeIfAbsent(targetProc, k -> new HashSet<>());
                  if(idx1 >= 0 && idx2 >= 0 && idx3 >= 0 && idx4 >= 0) {
                     AsmInstruction i1 = (AsmInstruction) initialLines.get(idx1).line;
                     AsmInstruction i2 = (AsmInstruction) initialLines.get(idx2).line;
                     AsmInstruction i3 = (AsmInstruction) initialLines.get(idx3).line;
                     AsmInstruction i4 = (AsmInstruction) initialLines.get(idx4).line;
                     if("lda".equalsIgnoreCase(i1.getCpuOpcode().getMnemonic()) &&
                        matchesVarLo(i1.getOperand1(), targetProc + ".return", retAddr, callerAliases) &&
                        "sta".equalsIgnoreCase(i2.getCpuOpcode().getMnemonic()) &&
                        "lda".equalsIgnoreCase(i3.getCpuOpcode().getMnemonic()) &&
                        matchesVarHi(i3.getOperand1(), targetProc + ".return", retAddr, callerAliases) &&
                        "sta".equalsIgnoreCase(i4.getCpuOpcode().getMnemonic())) {
                        handled.add(idx1);
                        handled.add(idx3);
                        continue;
                     }
                  }
                  if(idx1 >= 0 && initialLines.get(idx1).line instanceof AsmInstruction i1 &&
                     "lda".equalsIgnoreCase(i1.getCpuOpcode().getMnemonic()) &&
                     matchesVarLo(i1.getOperand1(), targetProc + ".return", retAddr, callerAliases) &&
                     idx2 >= 0 && initialLines.get(idx2).line instanceof AsmInstruction i2 &&
                     !"sta".equalsIgnoreCase(i2.getCpuOpcode().getMnemonic())) {
                     // Case 4B stores A/X back to return/return+1 right after JSR, so any subsequent reads in this block are valid
                     handled.add(idx1);
                     for(int k = idx1 + 1; k < initialLines.size(); k++) {
                        LineRef kr = initialLines.get(k);
                        if(kr.scope == null || !ref.scope.equals(kr.scope) || kr.line instanceof AsmScopeEnd) break;
                        if(kr.line instanceof AsmInstruction ki && ki.getCpuOpcode() != null &&
                           "jsr".equalsIgnoreCase(ki.getCpuOpcode().getMnemonic()) && targetProc.equals(ki.getOperand1())) {
                           break;
                        }
                        handled.add(k);
                     }
                  }
               }
            }
         }
      }

      // Verify no caller reads procName.return or procName.return+1 outside handled Step 4 patterns (e.g. high-byte-only readers)
      Set<String> checkProcs = new HashSet<>();
      for(String candProc : axReturnProcs) {
         if(unsafeAxReturnProcs.contains(candProc)) continue;
         Procedure p = procByName.get(candProc);
         if(p != null && p.isAxConvention()) continue; // Explicitly annotated __ax
         checkProcs.add(candProc);
      }
      if(!checkProcs.isEmpty()) {
         for(int i = 0; i < initialLines.size() && !checkProcs.isEmpty(); i++) {
            LineRef ref = initialLines.get(i);
            if(ref.line instanceof AsmInstruction inst && inst.getOperand1() != null) {
               Map<String, String> callerAliases = scopeAliases.get(ref.scope);
               String op = inst.getOperand1().trim();
               checkQualReturnRef(op, i, ref.scope, checkProcs, handledCallerReadIndices, unsafeAxReturnProcs);
               if(callerAliases != null) {
                  checkQualReturnRef(callerAliases.get(op), i, ref.scope, checkProcs, handledCallerReadIndices, unsafeAxReturnProcs);
               }
               String stripped = stripPlus1(op);
               if(stripped != null) {
                  checkQualReturnRef(stripped, i, ref.scope, checkProcs, handledCallerReadIndices, unsafeAxReturnProcs);
                  if(callerAliases != null) {
                     checkQualReturnRef(callerAliases.get(stripped), i, ref.scope, checkProcs, handledCallerReadIndices, unsafeAxReturnProcs);
                  }
               }
            }
         }
      }
      axReturnProcs.removeAll(unsafeAxReturnProcs);

      Set<AsmInstruction> transformedRts = new HashSet<>();
      Set<AsmInstruction> transformedReturnJsr = new HashSet<>();
      Set<String> paramInitializedProcs = new HashSet<>();

      while(passModified) {
         passModified = false;
         List<LineRef> allLines = buildAllLines();

         // -------------------------------------------------------------
         // Step 4 (pre-Step 1): Caller AX Return Consumption
         // Run before Step 1 so callers returning another AX callee's result
         // via `sta return; stx return+1` do not trigger Case 1C (`tax`).
         // -------------------------------------------------------------
         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref = allLines.get(i);
            if(ref.line instanceof AsmInstruction inst && "jsr".equalsIgnoreCase(inst.getCpuOpcode().getMnemonic())) {
               if(transformedReturnJsr.contains(inst)) {
                  continue;
               }
               String target = inst.getOperand1();
               if(target != null && axReturnProcs.contains(target.trim())) {
                  String procName = target.trim();
                  String retAddr = procReturnVarAddr.get(procName);
                  Map<String, String> callerAliases = scopeAliases.get(ref.scope);

                  int idx1 = nextInstIdx(allLines, i + 1);
                  int idx2 = (idx1 >= 0) ? nextInstIdx(allLines, idx1 + 1) : -1;
                  int idx3 = (idx2 >= 0) ? nextInstIdx(allLines, idx2 + 1) : -1;
                  int idx4 = (idx3 >= 0) ? nextInstIdx(allLines, idx3 + 1) : -1;

                  if(idx1 >= 0 && idx2 >= 0 && idx3 >= 0 && idx4 >= 0) {
                     AsmInstruction inst1 = (AsmInstruction) allLines.get(idx1).line;
                     AsmInstruction inst2 = (AsmInstruction) allLines.get(idx2).line;
                     AsmInstruction inst3 = (AsmInstruction) allLines.get(idx3).line;
                     AsmInstruction inst4 = (AsmInstruction) allLines.get(idx4).line;

                     // Case 4A: Storing return value to destination
                     if("lda".equalsIgnoreCase(inst1.getCpuOpcode().getMnemonic()) &&
                        matchesVarLo(inst1.getOperand1(), procName + ".return", retAddr, callerAliases) &&
                        "sta".equalsIgnoreCase(inst2.getCpuOpcode().getMnemonic()) &&
                        "lda".equalsIgnoreCase(inst3.getCpuOpcode().getMnemonic()) &&
                        matchesVarHi(inst3.getOperand1(), procName + ".return", retAddr, callerAliases) &&
                        "sta".equalsIgnoreCase(inst4.getCpuOpcode().getMnemonic())) {

                        allLines.get(idx1).chunk.getLines().remove(inst1);
                        allLines.get(idx3).chunk.getLines().remove(inst3);
                        CpuAddressingMode stMode = inst4.getCpuOpcode().getAddressingMode();
                        CpuOpcode stxOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("stx", stMode, false);
                        inst4.setCpuOpcode(stxOp);
                        inst2.setNoOptimize(true);
                        inst4.setNoOptimize(true);
                        transformedReturnJsr.add(inst);

                        passModified = true;
                        overallModified = true;
                        break;
                     }
                  }

                  // Case 4B: Caller tests or uses return value directly from memory without storing
                  if(idx1 >= 0 && allLines.get(idx1).line instanceof AsmInstruction inst1) {
                     if("lda".equalsIgnoreCase(inst1.getCpuOpcode().getMnemonic()) &&
                        matchesVarLo(inst1.getOperand1(), procName + ".return", retAddr, callerAliases)) {
                        if(idx2 >= 0 && allLines.get(idx2).line instanceof AsmInstruction inst2 &&
                           !"sta".equalsIgnoreCase(inst2.getCpuOpcode().getMnemonic())) {
                           CpuAddressingMode stMode = inst1.getCpuOpcode().getAddressingMode();
                           CpuOpcode staOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("sta", stMode, false);
                           CpuOpcode stxOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("stx", stMode, false);
                           String retLoOp = inst1.getOperand1();
                           String retHiOp = retLoOp + "+1";

                           int jsrIndex = ref.chunk.getLines().indexOf(inst);
                           if(jsrIndex >= 0) {
                              AsmInstruction staInst = new AsmInstruction(staOp, retLoOp, null, null);
                              staInst.setNoOptimize(true);
                              AsmInstruction stxInst = new AsmInstruction(stxOp, retHiOp, null, null);
                              stxInst.setNoOptimize(true);
                              ref.chunk.getLines().add(jsrIndex + 1, staInst);
                              ref.chunk.getLines().add(jsrIndex + 2, stxInst);
                              transformedReturnJsr.add(inst);
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

         if(passModified) continue;

         // -------------------------------------------------------------
         // Step 1: Callee AX Return Optimization
         // -------------------------------------------------------------
         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref = allLines.get(i);
            if(ref.scope != null && axReturnProcs.contains(ref.scope)) {
               if(ref.line instanceof AsmInstruction inst && "rts".equalsIgnoreCase(inst.getCpuOpcode().getMnemonic())) {
                  if(transformedRts.contains(inst)) {
                     continue;
                  }

                  String retAddr = procReturnVarAddr.get(ref.scope);
                  Map<String, String> aliases = scopeAliases.get(ref.scope);

                  int idx1 = prevInstIdx(allLines, i - 1);
                  boolean inSameScope = (idx1 >= 0 && ref.scope.equals(allLines.get(idx1).scope));

                  // Already returns in AX?
                  if(inSameScope) {
                     LineRef ref1 = allLines.get(idx1);
                     if(ref1.line instanceof AsmInstruction inst1) {
                        String mn1 = inst1.getCpuOpcode().getMnemonic().toLowerCase();
                        if("ldx".equals(mn1)) {
                           transformedRts.add(inst);
                           continue;
                        }
                        int idx0 = prevInstIdx(allLines, idx1 - 1);
                        if(idx0 >= 0 && ref.scope.equals(allLines.get(idx0).scope)) {
                           AsmInstruction inst0 = (AsmInstruction) allLines.get(idx0).line;
                           String mn0 = inst0.getCpuOpcode().getMnemonic().toLowerCase();
                           if("tax".equals(mn0) && "lda".equals(mn1)) {
                              transformedRts.add(inst);
                              continue;
                           }
                        }
                     }
                  }

                  // Case 1A: Constant return defined in .label return = <const> (ONLY when return var is constant, NOT a ZP address!)
                  Procedure curProcObj = procByName.get(ref.scope);
                  Variable retVarObj = (curProcObj != null) ? curProcObj.getLocalVariable("return") : null;
                  boolean isConstantReturnVar = (retVarObj != null && retVarObj.isKindConstant());
                  String retValDecl = (aliases != null) ? aliases.get("return") : null;
                  Long constVal = isConstantReturnVar ? parseConstValue(retValDecl) : null;
                  if(constVal != null && (!inSameScope || !(allLines.get(idx1).line instanceof AsmInstruction))) {
                     int rtsIndex = ref.chunk.getLines().indexOf(inst);
                     if(rtsIndex >= 0) {
                        AsmInstruction ldaRet = new AsmInstruction(ldaImm, "<" + retValDecl, null, null);
                        ldaRet.setNoOptimize(true);
                        AsmInstruction ldxRet = new AsmInstruction(ldxImm, ">" + retValDecl, null, null);
                        ldxRet.setNoOptimize(true);
                        ref.chunk.getLines().add(rtsIndex, ldaRet);
                        ref.chunk.getLines().add(rtsIndex + 1, ldxRet);
                        transformedRts.add(inst);
                        if(curProcObj != null) curProcObj.setAxConvention(true);
                        passModified = true;
                        overallModified = true;
                        break;
                     }
                  }

                  // If RTS is immediately preceded by a merge label (e.g. __breturn: rts), emit Case 1D (lda.z return; ldx.z return+1)
                  if(!inSameScope) {
                     if(retAddr != null) {
                        CpuOpcode ldaOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lda", CpuAddressingMode.ZP, false);
                        CpuOpcode ldxOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("ldx", CpuAddressingMode.ZP, false);
                        int rtsIndex = ref.chunk.getLines().indexOf(inst);
                        if(rtsIndex >= 0 && ldaOp != null && ldxOp != null) {
                           AsmInstruction ldaRet = new AsmInstruction(ldaOp, retAddr, null, null);
                           ldaRet.setNoOptimize(true);
                           AsmInstruction ldxRet = new AsmInstruction(ldxOp, retAddr + "+1", null, null);
                           ldxRet.setNoOptimize(true);
                           ref.chunk.getLines().add(rtsIndex, ldaRet);
                           ref.chunk.getLines().add(rtsIndex + 1, ldxRet);
                           transformedRts.add(inst);
                           if(curProcObj != null) curProcObj.setAxConvention(true);
                           passModified = true;
                           overallModified = true;
                           break;
                        }
                     }
                     transformedRts.add(inst);
                     continue;
                  }
                  LineRef ref1 = allLines.get(idx1);
                  if(!(ref1.line instanceof AsmInstruction inst1)) {
                     transformedRts.add(inst);
                     continue;
                  }

                  // Case 1B: Constant return sequence:
                  // lda #<const ; sta return ; lda #>const ; sta return+1 ; rts
                  if("sta".equalsIgnoreCase(inst1.getCpuOpcode().getMnemonic()) &&
                     matchesVarHi(inst1.getOperand1(), "return", retAddr, aliases)) {

                     int idx2 = prevInstIdx(allLines, idx1 - 1);
                     int idx3 = (idx2 >= 0) ? prevInstIdx(allLines, idx2 - 1) : -1;
                     int idx4 = (idx3 >= 0) ? prevInstIdx(allLines, idx3 - 1) : -1;

                     if(idx2 >= 0 && idx3 >= 0 && idx4 >= 0) {
                        AsmInstruction inst2 = (AsmInstruction) allLines.get(idx2).line;
                        AsmInstruction inst3 = (AsmInstruction) allLines.get(idx3).line;
                        AsmInstruction inst4 = (AsmInstruction) allLines.get(idx4).line;

                        if("lda".equalsIgnoreCase(inst2.getCpuOpcode().getMnemonic()) &&
                           inst2.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.IMM) &&
                           "sta".equalsIgnoreCase(inst3.getCpuOpcode().getMnemonic()) &&
                           matchesVarLo(inst3.getOperand1(), "return", retAddr, aliases) &&
                           "lda".equalsIgnoreCase(inst4.getCpuOpcode().getMnemonic()) &&
                           inst4.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.IMM)) {

                           inst2.setCpuOpcode(ldxImm);
                           inst2.setNoOptimize(true);
                           inst4.setNoOptimize(true);
                           allLines.get(idx3).chunk.getLines().remove(inst3);
                           ref1.chunk.getLines().remove(inst1);
                           transformedRts.add(inst);
                           if(curProcObj != null) curProcObj.setAxConvention(true);
                           passModified = true;
                           overallModified = true;
                           break;
                        }
                     }

                     // Case 1C: Dynamic return ending in sta return+1:
                     // Insert: tax ; lda.z return
                     if(retAddr == null) {
                        transformedRts.add(inst);
                        continue;
                     }
                     CpuAddressingMode ldaMode = inst1.getCpuOpcode().getAddressingMode();
                     CpuOpcode ldaOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lda", ldaMode, false);
                     String retLoOp = retAddr;
                     int rtsIndex = ref.chunk.getLines().indexOf(inst);
                     if(rtsIndex >= 0) {
                        AsmInstruction taxInst = new AsmInstruction(taxOp, null, null, null);
                        taxInst.setNoOptimize(true);
                        AsmInstruction ldaRet = new AsmInstruction(ldaOp, retLoOp, null, null);
                        ldaRet.setNoOptimize(true);
                        ref.chunk.getLines().add(rtsIndex, taxInst);
                        ref.chunk.getLines().add(rtsIndex + 1, ldaRet);
                        transformedRts.add(inst);
                        if(curProcObj != null) curProcObj.setAxConvention(true);
                        passModified = true;
                        overallModified = true;
                        break;
                     }
                  } else {
                     // Case 1D: Any other return (e.g. rol.z return+1; rts):
                     // Insert: lda.z return ; ldx.z return+1
                     if(retAddr == null) {
                        transformedRts.add(inst);
                        continue;
                     }
                     CpuAddressingMode ldaMode = CpuAddressingMode.ZP;
                     CpuOpcode ldaOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lda", ldaMode, false);
                     CpuOpcode ldxOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("ldx", ldaMode, false);
                     String retLoOp = retAddr;
                     String retHiOp = retAddr + "+1";
                     int rtsIndex = ref.chunk.getLines().indexOf(inst);
                     if(rtsIndex >= 0) {
                        AsmInstruction ldaRet = new AsmInstruction(ldaOp, retLoOp, null, null);
                        ldaRet.setNoOptimize(true);
                        AsmInstruction ldxRet = new AsmInstruction(ldxOp, retHiOp, null, null);
                        ldxRet.setNoOptimize(true);
                        ref.chunk.getLines().add(rtsIndex, ldaRet);
                        ref.chunk.getLines().add(rtsIndex + 1, ldxRet);
                        transformedRts.add(inst);
                        if(curProcObj != null) curProcObj.setAxConvention(true);
                        passModified = true;
                        overallModified = true;
                        break;
                     }
                  }
               }
            }
         }

         if(passModified) continue;

         // -------------------------------------------------------------
         // Step 2: Callee Parameter Receiving in AX
         // -------------------------------------------------------------
         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref = allLines.get(i);
            if(ref.scope != null && axParamProcs.contains(ref.scope) && ref.line instanceof AsmInstruction) {
               if(hasEarlierInstInScope(allLines, i, ref.scope)) {
                  continue; // Not the first instruction in scope
               }

               if(paramInitializedProcs.contains(ref.scope)) {
                  continue;
               }

               String pName = procParamVarName.get(ref.scope);
               String pAddr = procParamVarAddr.get(ref.scope);
               Map<String, String> calleeAliases = scopeAliases.get(ref.scope);

               int idx1 = i;
               int idx2 = nextInstIdx(allLines, idx1 + 1);
               int idx3 = (idx2 >= 0) ? nextInstIdx(allLines, idx2 + 1) : -1;
               int idx4 = (idx3 >= 0) ? nextInstIdx(allLines, idx3 + 1) : -1;

               boolean matchedDirect = false;
               if(idx2 >= 0 && idx3 >= 0 && idx4 >= 0) {
                  AsmInstruction inst1 = (AsmInstruction) allLines.get(idx1).line;
                  AsmInstruction inst2 = (AsmInstruction) allLines.get(idx2).line;
                  AsmInstruction inst3 = (AsmInstruction) allLines.get(idx3).line;
                  AsmInstruction inst4 = (AsmInstruction) allLines.get(idx4).line;

                  if("lda".equalsIgnoreCase(inst1.getCpuOpcode().getMnemonic()) &&
                     matchesVarLo(inst1.getOperand1(), pName, pAddr, calleeAliases) &&
                     "sta".equalsIgnoreCase(inst2.getCpuOpcode().getMnemonic()) &&
                     "lda".equalsIgnoreCase(inst3.getCpuOpcode().getMnemonic()) &&
                     matchesVarHi(inst3.getOperand1(), pName, pAddr, calleeAliases) &&
                     "sta".equalsIgnoreCase(inst4.getCpuOpcode().getMnemonic())) {

                     allLines.get(idx1).chunk.getLines().remove(inst1);
                     allLines.get(idx3).chunk.getLines().remove(inst3);
                     CpuAddressingMode stMode = inst4.getCpuOpcode().getAddressingMode();
                     CpuOpcode stxOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("stx", stMode, false);
                     inst4.setCpuOpcode(stxOp);
                     inst2.setNoOptimize(true);
                     inst4.setNoOptimize(true);

                     matchedDirect = true;
                     paramInitializedProcs.add(ref.scope);
                     passModified = true;
                     overallModified = true;
                     break;
                  }
               }

               if(!matchedDirect && pAddr != null) {
                  CpuAddressingMode stMode = CpuAddressingMode.ZP;
                  CpuOpcode staOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("sta", stMode, false);
                  CpuOpcode stxOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("stx", stMode, false);
                  String pLoOp = pAddr;
                  String pHiOp = pAddr + "+1";

                  int entryIndex = ref.chunk.getLines().indexOf(ref.line);
                  if(entryIndex >= 0) {
                     AsmInstruction staInst = new AsmInstruction(staOp, pLoOp, null, null);
                     staInst.setNoOptimize(true);
                     AsmInstruction stxInst = new AsmInstruction(stxOp, pHiOp, null, null);
                     stxInst.setNoOptimize(true);
                     ref.chunk.getLines().add(entryIndex, staInst);
                     ref.chunk.getLines().add(entryIndex + 1, stxInst);
                     paramInitializedProcs.add(ref.scope);
                     passModified = true;
                     overallModified = true;
                     break;
                  }
               }
            }
         }

         if(passModified) continue;

         // -------------------------------------------------------------
         // Step 3: Caller Parameter Passing in AX (1st 16-bit param)
         // -------------------------------------------------------------
         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref = allLines.get(i);
            if(ref.line instanceof AsmInstruction inst) {
               String mn = inst.getCpuOpcode().getMnemonic().toLowerCase();
               if("jsr".equals(mn) || "jmp".equals(mn)) {
                  String target = inst.getOperand1();
                  if(target != null && axParamProcs.contains(target.trim())) {
                     String procName = target.trim();
                     String pName = procParamVarName.get(procName);
                     String pAddr = procParamVarAddr.get(procName);
                     Map<String, String> callerAliases = scopeAliases.get(ref.scope);

                     int idx1 = prevInstIdx(allLines, i - 1);
                     int idx2 = (idx1 >= 0) ? prevInstIdx(allLines, idx1 - 1) : -1;
                     int idx3 = (idx2 >= 0) ? prevInstIdx(allLines, idx2 - 1) : -1;
                     int idx4 = (idx3 >= 0) ? prevInstIdx(allLines, idx3 - 1) : -1;

                     if(idx1 >= 0 && idx2 >= 0 && idx3 >= 0 && idx4 >= 0) {
                        AsmInstruction inst1 = (AsmInstruction) allLines.get(idx1).line;
                        AsmInstruction inst2 = (AsmInstruction) allLines.get(idx2).line;
                        AsmInstruction inst3 = (AsmInstruction) allLines.get(idx3).line;
                        AsmInstruction inst4 = (AsmInstruction) allLines.get(idx4).line;

                        String qualifiedParam = procName + "." + pName;
                        if("sta".equalsIgnoreCase(inst1.getCpuOpcode().getMnemonic()) &&
                           matchesVarHi(inst1.getOperand1(), qualifiedParam, pAddr, callerAliases) &&
                           "lda".equalsIgnoreCase(inst2.getCpuOpcode().getMnemonic()) &&
                           "sta".equalsIgnoreCase(inst3.getCpuOpcode().getMnemonic()) &&
                           matchesVarLo(inst3.getOperand1(), qualifiedParam, pAddr, callerAliases) &&
                           "lda".equalsIgnoreCase(inst4.getCpuOpcode().getMnemonic())) {

                           allLines.get(idx1).chunk.getLines().remove(inst1);
                           CpuAddressingMode ldaMode = inst2.getCpuOpcode().getAddressingMode();
                           CpuOpcode ldxOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("ldx", ldaMode, false);
                           inst2.setCpuOpcode(ldxOp);
                           inst4.setNoOptimize(true);
                           inst2.setNoOptimize(true);
                           allLines.get(idx3).chunk.getLines().remove(inst3);

                           passModified = true;
                           overallModified = true;
                           break;
                        }
                     }
                  }
               }
            }
         }

         // End of Step 3
      }

      return overallModified;
   }
}
