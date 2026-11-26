package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.CallGraph;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.Registers;
import dk.camelot64.kickc.model.StructVariableMemberUnwinding;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolTypeStruct;
import dk.camelot64.kickc.model.values.SymbolVariableRef;

import java.util.*;

/**
 * Multi-Return Struct ABI optimization for 2-byte and 3-byte unwound struct returns
 * (2x or 3x 1-byte fields) whose return slots remained in zero page after Pass 4 register allocation.
 * Maps return field 0 -> A, field 1 -> X, field 2 -> Y across non-recursive, non-interrupt callees
 * and all their JSR call sites when callee tail registers and caller post-call reads are interference-free.
 */
public class Pass5MultiReturnStructOptimization extends Pass5AsmOptimization {

   private static final String[] ABI_REGS = {"A", "X", "Y"};

   private static final class ReturnField {
      final int index;
      final String targetReg;
      final String asmName;
      final String qualName;
      final int zpAddr;
      final String zpHex;
      final String canon;

      ReturnField(int index, String targetReg, String asmName, String qualName, int zpAddr, String zpHex, String canon) {
         this.index = index;
         this.targetReg = targetReg;
         this.asmName = asmName;
         this.qualName = qualName;
         this.zpAddr = zpAddr;
         this.zpHex = zpHex;
         this.canon = canon;
      }

      boolean matchesOperand(String op, CanonicalResolver resolver) {
         if(op == null) return false;
         String t = op.trim();
         if(t.equals(asmName) || t.equals(qualName)) return true;
         if(resolver != null) {
            String c = resolver.resolve(t);
            if(c.equals(canon) || c.equalsIgnoreCase(zpHex)) return true;
         }
         return false;
      }
   }

   private static final class TailPatch {
      final int storeIdx;
      final String replacementMnemonic; // null => tombstone

      TailPatch(int storeIdx, String replacementMnemonic) {
         this.storeIdx = storeIdx;
         this.replacementMnemonic = replacementMnemonic;
      }
   }

   public Pass5MultiReturnStructOptimization(Program program) {
      super(program);
   }

   @Override
   public boolean optimize() {
      if(getProgram().getScope() == null) return false;
      StructVariableMemberUnwinding unwinding = getProgram().getStructVariableMemberUnwinding();
      if(unwinding == null) return false;

      List<LineRef> allLines = getSharedAllLines();
      if(allLines.isEmpty()) return false;
      Map<String, CanonicalResolver> scopeResolvers = getSharedScopeResolvers(allLines);

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

      CallGraph callGraph = getProgram().getCallGraph();
      boolean modified = false;

      for(Procedure proc : getProgram().getScope().getAllProcedures(true)) {
         if(proc == null) continue;
         String procName = proc.getLocalName();
         if("main".equals(procName) || "__start".equals(procName)) continue;
         if(proc.getInterruptType() != null || proc.isDeclaredIntrinsic()) continue;
         if(Procedure.CallingConvention.STACK_CALL.equals(proc.getCallingConvention())) continue;
         if(callGraph != null && callGraph.getRecursiveCalls(proc.getRef()).contains(proc.getRef())) continue;
         if(Pass2ConstantIdentification.isAddressOfUsed(proc.getRef(), getProgram())) continue;

         Variable procReturnVar = proc.getLocalVariable("return");
         if(procReturnVar == null || !procReturnVar.isStructUnwind()
               || !(procReturnVar.getType() instanceof SymbolTypeStruct)) {
            continue;
         }
         StructVariableMemberUnwinding.VariableUnwinding retUnwinding = unwinding.getVariableUnwinding(procReturnVar.getRef());
         if(retUnwinding == null) continue;
         List<String> memberNames = retUnwinding.getMemberNames();
         if(memberNames == null || (memberNames.size() != 2 && memberNames.size() != 3)) continue;

         int[] range = scopeRanges.get(procName);
         if(range == null) continue;
         CanonicalResolver calleeResolver = scopeResolvers.getOrDefault(procName, scopeResolvers.get(""));

         // Identify which return fields remain in zero page vs already in canonical A/X/Y
         List<ReturnField> zpFields = new ArrayList<>();
         boolean fieldsEligible = true;
         for(int idx = 0; idx < memberNames.size(); idx++) {
            SymbolVariableRef memberRef = retUnwinding.getMemberUnwound(memberNames.get(idx));
            Variable memberVar = memberRef != null ? getProgram().getScope().getVariable(memberRef) : null;
            if(memberVar == null || memberVar.getType().getSizeBytes() != 1
                  || memberVar.isVolatile() || memberVar.isKindLoadStore()) {
               fieldsEligible = false;
               break;
            }
            Registers.Register alloc = memberVar.getAllocation();
            String expectedReg = ABI_REGS[idx];
            if(alloc instanceof Registers.RegisterZpMem zpReg) {
               String asmName = memberVar.getAsmName() != null ? memberVar.getAsmName() : memberVar.getLocalName();
               String qualName = procName + "." + asmName;
               String zpHex = String.format("$%02x", zpReg.getZp());
               String canon = calleeResolver != null ? calleeResolver.resolve(asmName) : zpHex;
               zpFields.add(new ReturnField(idx, expectedReg, asmName, qualName, zpReg.getZp(), zpHex, canon));
            } else if(alloc != null && expectedReg.equals(registerName(alloc))) {
               // Already allocated in its canonical ABI register
            } else {
               // Allocated in a conflicting register or main memory; skip safely
               fieldsEligible = false;
               break;
            }
         }
         if(!fieldsEligible || zpFields.isEmpty()) continue;

         // Reject inline KickAsm referencing this procedure or its return fields
         boolean inlineAsmUnsafe = false;
         for(int idx : inlineKickAsmIndices) {
            if(idx >= range[0] && idx <= range[1]) continue;
            if(allLines.get(idx).line instanceof AsmInlineKickAsm inlineAsm) {
               String code = inlineAsm.getKickAsmCode();
               if(code != null) {
                  if(code.contains(procName)) {
                     inlineAsmUnsafe = true;
                     break;
                  }
                  for(ReturnField rf : zpFields) {
                     if(code.contains(rf.qualName)) {
                        inlineAsmUnsafe = true;
                        break;
                     }
                  }
               }
            }
            if(inlineAsmUnsafe) break;
         }
         if(inlineAsmUnsafe) continue;

         // Reject tail-call JMPs targeting procName
         List<Integer> procJmps = jmpSites.get(procName);
         boolean hasTailJmp = false;
         if(procJmps != null) {
            for(int i : procJmps) {
               if(i >= range[0] && i <= range[1]) continue;
               if(allLines.get(i).line instanceof AsmInstruction) {
                  hasTailJmp = true;
                  break;
               }
            }
         }
         if(hasTailJmp) continue;

         // 1. Verify callee: every RTS must have a contiguous tail block storing all ZP return fields cleanly
         List<TailPatch> tailPatches = new ArrayList<>();
         Set<Integer> calleeStoreIdxSet = new HashSet<>();
         boolean calleeOk = true;
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
               if(("jsr".equals(m) || "jmp".equals(m)) && op != null && procName.equals(op.trim())) {
                  calleeOk = false;
                  break;
               }
               if("rts".equals(m)) {
                  rtsCount++;
                  if(!analyzeCalleeTailBeforeRts(allLines, range[0], i, zpFields, calleeResolver, tailPatches, calleeStoreIdxSet)) {
                     calleeOk = false;
                     break;
                  }
               }
            }
         }
         if(!calleeOk || rtsCount == 0 || tailPatches.isEmpty()) continue;

         // Reject if callee accesses any ZP return field outside the verified tail stores
         for(int i = range[0] + 1; i < range[1]; i++) {
            if(calleeStoreIdxSet.contains(i)) continue;
            AsmLine l = allLines.get(i).line;
            if(l instanceof AsmInstruction inst && inst.getOperand1() != null) {
               String op = inst.getOperand1().trim();
               String opBase = getBaseVar(op);
               for(ReturnField rf : zpFields) {
                  if(rf.matchesOperand(op, calleeResolver) || rf.matchesOperand(opBase, calleeResolver)) {
                     calleeOk = false;
                     break;
                  }
               }
               if(!calleeOk) break;
            }
         }
         if(!calleeOk) continue;

         // Collect caller aliases per scope for each ZP return field
         Map<ReturnField, Map<String, Set<String>>> fieldCallerAliases = new HashMap<>();
         for(ReturnField rf : zpFields) {
            Map<String, Set<String>> aliasesByScope = new HashMap<>();
            Set<String> candScopes = scopesReferencingRhs.get(rf.qualName);
            if(candScopes != null) {
               for(String sc : candScopes) {
                  if(procName.equals(sc)) continue;
                  Map<String, List<String>> rev = scopeReverseDecls.get(sc);
                  if(rev == null) continue;
                  Set<String> aliases = new HashSet<>();
                  aliases.add(rf.qualName);
                  ArrayDeque<String> queue = new ArrayDeque<>();
                  queue.add(rf.qualName);
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
                     aliasesByScope.put(sc, aliases);
                  }
               }
            }
            fieldCallerAliases.put(rf, aliasesByScope);
         }

         // 2. Verify all callers
         List<Integer> procJsrs = jsrSites.get(procName);
         if(procJsrs == null || procJsrs.isEmpty()) continue;

         List<Integer> callerLoadIndices = new ArrayList<>();
         Set<Integer> callerLoadSet = new HashSet<>();
         Set<String> patchedCallerScopes = new LinkedHashSet<>();
         boolean callersOk = true;
         int callCount = 0;

         for(int i : procJsrs) {
            if(i >= range[0] && i <= range[1]) continue;
            LineRef ref = allLines.get(i);
            if(!(ref.line instanceof AsmInstruction)) continue;
            callCount++;
            String refScope = ref.scope != null ? ref.scope : "";
            CanonicalResolver callerResolver = scopeResolvers.getOrDefault(refScope, scopeResolvers.get(""));

            if(!verifyCallSite(allLines, i, refScope, callerResolver, zpFields, fieldCallerAliases, callerLoadIndices, callerLoadSet)) {
               callersOk = false;
               break;
            }
            patchedCallerScopes.add(refScope);
         }
         if(!callersOk || callCount == 0 || callerLoadIndices.isEmpty()) continue;

         // Ensure no other instruction in the program references any ZP return field or its aliases
         for(ReturnField rf : zpFields) {
            List<Integer> directRefs = dotOperandRefs.get(rf.qualName);
            if(directRefs != null) {
               for(int idx : directRefs) {
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
            if(!callersOk) break;

            Map<String, Set<String>> aliasesByScope = fieldCallerAliases.get(rf);
            if(aliasesByScope != null) {
               for(Map.Entry<String, Set<String>> aliasEntry : aliasesByScope.entrySet()) {
                  Map<String, List<Integer>> scRefs = scopeOperandRefs.get(aliasEntry.getKey());
                  if(scRefs == null) continue;
                  for(String alias : aliasEntry.getValue()) {
                     if(rf.qualName.equals(alias)) continue;
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
            if(!callersOk) break;
         }
         if(!callersOk) continue;

         // 3. Apply transformation in callee and callers
         for(TailPatch tp : tailPatches) {
            if(tp.replacementMnemonic == null) {
               tombstoneLine(allLines, tp.storeIdx);
            } else {
               CpuOpcode xferOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode(tp.replacementMnemonic, CpuAddressingMode.NON, false);
               if(xferOp != null && allLines.get(tp.storeIdx).line instanceof AsmInstruction storeInst) {
                  storeInst.setCpuOpcode(xferOp);
                  storeInst.setOperand1(null);
               }
            }
         }
         for(int loadIdx : callerLoadIndices) {
            tombstoneLine(allLines, loadIdx);
         }
         markScopeDirty(procName);
         for(String cs : patchedCallerScopes) {
            markScopeDirty(cs);
         }
         getLog().append("Multi-return ABI: Optimized " + procName + " (" + zpFields.size() + " ZP fields mapped to registers at " + callerLoadIndices.size() + " call loads)");
         modified = true;
      }

      if(modified) {
         invalidateRoundStructure();
      }
      return modified;
   }

   private boolean analyzeCalleeTailBeforeRts(
         List<LineRef> allLines,
         int scopeStart,
         int rtsIdx,
         List<ReturnField> zpFields,
         CanonicalResolver calleeResolver,
         List<TailPatch> outPatches,
         Set<Integer> outStoreIdxSet) {
      // Find contiguous tail block before RTS (stopping at labels, branches, jumps, calls, inline asm, scope start)
      int tailStart = rtsIdx - 1;
      while(tailStart > scopeStart) {
         AsmLine l = allLines.get(tailStart).line;
         if(l instanceof AsmComment || l instanceof AsmConstant || l instanceof AsmLabelDecl) {
            tailStart--;
            continue;
         }
         if(l instanceof AsmLabel || l instanceof AsmInlineKickAsm || l instanceof AsmScopeBegin || l instanceof AsmScopeEnd) {
            tailStart++;
            break;
         }
         if(l instanceof AsmInstruction inst) {
            if(inst.getCpuOpcode() == null || inst.isNoOptimize() || inst.getCpuOpcode().isJump() || inst.getCpuOpcode().getMnemonic().startsWith("b")) {
               tailStart++;
               break;
            }
            tailStart--;
            continue;
         }
         tailStart++;
         break;
      }
      if(tailStart <= scopeStart) tailStart = scopeStart + 1;

      Map<ReturnField, Integer> storeIdxByField = new LinkedHashMap<>();
      for(int k = tailStart; k < rtsIdx; k++) {
         AsmLine l = allLines.get(k).line;
         if(!(l instanceof AsmInstruction inst) || inst.getCpuOpcode() == null || inst.isNoOptimize()) continue;
         String mnem = inst.getCpuOpcode().getMnemonic();
         if(("sta".equals(mnem) || "stx".equals(mnem) || "sty".equals(mnem))
               && inst.getCpuOpcode().getAddressingMode() == CpuAddressingMode.ZP) {
            String op = inst.getOperand1();
            for(ReturnField rf : zpFields) {
               if(rf.matchesOperand(op, calleeResolver)) {
                  if(storeIdxByField.containsKey(rf)) {
                     // Multiple stores to same field in tail -> conservative reject
                     return false;
                  }
                  storeIdxByField.put(rf, k);
               }
            }
         }
      }
      if(storeIdxByField.size() != zpFields.size()) {
         return false;
      }

      // Check that each field's target register stays intact from its store (or transfer) down to RTS
      // and doesn't clobber any other return field's register
      List<TailPatch> localPatches = new ArrayList<>();
      Set<String> liveTargetRegs = new HashSet<>();
      for(int k = tailStart; k < rtsIdx; k++) {
         AsmLine l = allLines.get(k).line;
         if(!(l instanceof AsmInstruction inst) || inst.getCpuOpcode() == null) continue;

         ReturnField matchedField = null;
         for(Map.Entry<ReturnField, Integer> e : storeIdxByField.entrySet()) {
            if(e.getValue() == k) {
               matchedField = e.getKey();
               break;
            }
         }

         if(matchedField != null) {
            String sm = inst.getCpuOpcode().getMnemonic();
            String srcReg = "sta".equals(sm) ? "A" : ("stx".equals(sm) ? "X" : "Y");
            if(srcReg.equals(matchedField.targetReg)) {
               localPatches.add(new TailPatch(k, null));
            } else {
               String xfer = getTransferMnemonic(srcReg, matchedField.targetReg);
               if(xfer == null) return false;
               CpuOpcode xferOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode(xfer, CpuAddressingMode.NON, false);
               if(xferOp == null) return false;
               if(liveTargetRegs.contains(matchedField.targetReg)) {
                  return false; // Writing targetReg would clobber an earlier return field
               }
               localPatches.add(new TailPatch(k, xfer));
            }
            liveTargetRegs.add(matchedField.targetReg);
         } else {
            var clobber = inst.getCpuOpcode().getClobber();
            if(liveTargetRegs.contains("A") && clobber.isRegisterA()) return false;
            if(liveTargetRegs.contains("X") && clobber.isRegisterX()) return false;
            if(liveTargetRegs.contains("Y") && clobber.isRegisterY()) return false;
         }
      }

      outPatches.addAll(localPatches);
      outStoreIdxSet.addAll(storeIdxByField.values());
      return true;
   }

   private boolean verifyCallSite(
         List<LineRef> allLines,
         int jsrIdx,
         String callerScope,
         CanonicalResolver callerResolver,
         List<ReturnField> zpFields,
         Map<ReturnField, Map<String, Set<String>>> fieldCallerAliases,
         List<Integer> outLoadIndices,
         Set<Integer> outLoadSet) {
      Set<ReturnField> loadedFields = new HashSet<>();
      Set<String> clobberedRegs = new HashSet<>();
      int maxPostIdx = jsrIdx;

      for(int k = jsrIdx + 1; k < allLines.size(); k++) {
         LineRef lr = allLines.get(k);
         if(callerScope != null && !callerScope.isEmpty() && !callerScope.equals(lr.scope)) break;
         AsmLine l = lr.line;
         if(l instanceof AsmComment || l instanceof AsmConstant || l instanceof AsmLabelDecl) continue;
         if(!(l instanceof AsmInstruction inst) || inst.getCpuOpcode() == null || inst.isNoOptimize()) break;
         String mnem = inst.getCpuOpcode().getMnemonic();
         if(inst.getCpuOpcode().isJump() || mnem.startsWith("b")) break;

         if(("lda".equals(mnem) || "ldx".equals(mnem) || "ldy".equals(mnem))
               && inst.getCpuOpcode().getAddressingMode() == CpuAddressingMode.ZP) {
            String op = inst.getOperand1() != null ? inst.getOperand1().trim() : null;
            ReturnField matched = null;
            if(op != null) {
               for(ReturnField rf : zpFields) {
                  Set<String> aliases = fieldCallerAliases.get(rf).get(callerScope);
                  if(rf.qualName.equals(op) || (aliases != null && aliases.contains(op))) {
                     matched = rf;
                     break;
                  }
               }
            }
            if(matched != null) {
               String dstReg = "lda".equals(mnem) ? "A" : ("ldx".equals(mnem) ? "X" : "Y");
               if(!dstReg.equals(matched.targetReg) || clobberedRegs.contains(dstReg)) {
                  return false;
               }
               if(areNZFlagsLiveAfter(allLines, k, callerScope, getProgram())) {
                  return false;
               }
               loadedFields.add(matched);
               outLoadIndices.add(k);
               outLoadSet.add(k);
               maxPostIdx = k;
               if(loadedFields.size() == zpFields.size()) {
                  break;
               }
               continue;
            }
         }

         var clobber = inst.getCpuOpcode().getClobber();
         if(clobber.isRegisterA()) clobberedRegs.add("A");
         if(clobber.isRegisterX()) clobberedRegs.add("X");
         if(clobber.isRegisterY()) clobberedRegs.add("Y");
      }

      if(loadedFields.isEmpty()) {
         return false;
      }

      for(ReturnField rf : zpFields) {
         if(!loadedFields.contains(rf)) {
            if(!isRegDeadAlongPath(rf.targetReg, allLines, jsrIdx + 1, new HashSet<>(), callerScope, getProgram())) {
               return false;
            }
         }
         if(!isZpAddrDeadAlongPath(allLines, maxPostIdx + 1, rf.zpAddr, rf.zpHex, rf.canon, callerResolver, callerScope, new HashSet<>())) {
            return false;
         }
      }
      return true;
   }

   private static String getTransferMnemonic(String srcReg, String dstReg) {
      if("X".equals(srcReg) && "A".equals(dstReg)) return "txa";
      if("Y".equals(srcReg) && "A".equals(dstReg)) return "tya";
      if("A".equals(srcReg) && "X".equals(dstReg)) return "tax";
      if("A".equals(srcReg) && "Y".equals(dstReg)) return "tay";
      return null;
   }

   private static String registerName(Registers.Register reg) {
      if(reg == null) return null;
      return switch(reg.getType()) {
         case REG_A -> "A";
         case REG_X -> "X";
         case REG_Y -> "Y";
         default -> null;
      };
   }

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
                  if(dk.camelot64.kickc.model.types.SymbolType.VOID.equals(callerProc.getReturnType())) return true;
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
