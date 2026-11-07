package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;

import java.util.*;

/**
 * Assembler Peephole Optimization: Dead Store Elimination for Procedure-Local SSA Temporaries.
 * Procedure-local variables matching __\d+ (e.g. __87, __88) that are declared locally via
 * .label __NN = ... and NEVER read, aliased, or address-taken anywhere in the procedure scope are completely dead.
 * Any stores to them (sta.z __NN, sta.z __NN+1, stx, sty) are dead and can be eliminated.
 * Additionally, if a dead store is immediately preceded by an immediate load (e.g. lda #0) and
 * immediately followed by an instruction that overwrites that register without reading it,
 * the dead immediate load is eliminated as well.
 */
public class Pass5DeadStoreElimination extends Pass5AsmOptimization {

   private static class LineRef {
      final AsmChunk chunk;
      final AsmLine line;

      LineRef(AsmChunk chunk, AsmLine line) {
         this.chunk = chunk;
         this.line = line;
      }
   }

   public Pass5DeadStoreElimination(Program program) {
      super(program);
   }

   private static String getBaseVar(String op) {
      if(op == null) return "";
      int idx = op.indexOf('+');
      if(idx != -1) return op.substring(0, idx).trim();
      int minusIdx = op.indexOf('-');
      if(minusIdx != -1) return op.substring(0, minusIdx).trim();
      return op.trim();
   }

   private static boolean isExactVarMatch(String operand, String var) {
      if(operand == null || var == null) return false;
      String op = operand.trim();
      if(op.startsWith(".z ")) op = op.substring(3).trim();
      return op.equals(var);
   }

   private static boolean doesInstructionReferenceVar(AsmInstruction inst, String var) {
      if(inst == null) return false;
      String op = inst.getOperand1();
      if(op == null) return false;
      if(op.startsWith(".z ")) op = op.substring(3).trim();
      if(op.equals(var)) return true;
      if(op.startsWith(var + ",") || op.startsWith(var + ".")) return true;
      return false;
   }

   private static boolean isSsaTemp(String name) {
      if(name == null) return false;
      String base = getBaseVar(name);
      int dotIdx = base.lastIndexOf('.');
      if(dotIdx != -1) {
         base = base.substring(dotIdx + 1);
      }
      return base.matches("__\\d+");
   }

   private static boolean overwritesRegister(AsmInstruction inst, String reg) {
      if(inst == null || inst.getCpuOpcode() == null) return false;
      String mnem = inst.getCpuOpcode().getMnemonic();
      if(reg.equals("A")) {
         return mnem.equals("lda") || mnem.equals("pla") || mnem.equals("jsr");
      } else if(reg.equals("X")) {
         return mnem.equals("ldx") || mnem.equals("jsr");
      } else if(reg.equals("Y")) {
         return mnem.equals("ldy") || mnem.equals("jsr");
      }
      return false;
   }

   private static Integer parseAddress(String val) {
      if(val == null) return null;
      String t = val.trim();
      try {
         if(t.startsWith("$")) {
            return Integer.parseInt(t.substring(1), 16);
         } else if(t.startsWith("0x") || t.startsWith("0X")) {
            return Integer.parseInt(t.substring(2), 16);
         } else if(t.matches("\\d+")) {
            return Integer.parseInt(t);
         }
      } catch(Exception e) {}
      return null;
   }

   @Override
   public boolean optimize() {
      boolean overallModified = false;
      boolean passModified = true;

      while(passModified) {
         passModified = false;

         // Build a flat sequential list of all lines across all chunks
         List<LineRef> allLines = new ArrayList<>();
         for(AsmChunk chunk : getAsmProgram().getChunks()) {
            for(AsmLine line : chunk.getLines()) {
               allLines.add(new LineRef(chunk, line));
            }
         }

         // 0. Collect physical addresses bound to non-SSA variables and call graph
         Set<Integer> trulyGlobalPhysicalAddrs = new HashSet<>();
         Map<String, Set<Integer>> scopeNamedPhysicalAddrs = new HashMap<>();
         Map<String, Set<String>> directCalls = new HashMap<>();
         Map<String, List<LineRef>> scopeLinesMap = new LinkedHashMap<>();

         String scanScope = "";
         for(LineRef ref : allLines) {
            if(ref.line instanceof AsmScopeBegin) {
               scanScope = ((AsmScopeBegin) ref.line).getLabel();
            } else if(ref.line instanceof AsmScopeEnd) {
               scanScope = "";
            } else if(scanScope != null && !scanScope.isEmpty()) {
               scopeLinesMap.computeIfAbsent(scanScope, k -> new ArrayList<>()).add(ref);
            }

            if(ref.line instanceof AsmLabelDecl) {
               AsmLabelDecl decl = (AsmLabelDecl) ref.line;
               String name = decl.getName().trim();
               if(!isSsaTemp(name)) {
                  String val = decl.getValue().trim();
                  Integer addr = parseAddress(val);
                  if(addr != null) {
                     Set<Integer> targetSet = (scanScope != null && !scanScope.isEmpty())
                        ? scopeNamedPhysicalAddrs.computeIfAbsent(scanScope, k -> new HashSet<>())
                        : trulyGlobalPhysicalAddrs;
                     targetSet.add(addr);
                     targetSet.add(addr + 1);
                     targetSet.add(addr + 2);
                     targetSet.add(addr + 3);
                  }
               }
            } else if(ref.line instanceof AsmInstruction) {
               AsmInstruction inst = (AsmInstruction) ref.line;
               if(inst.getCpuOpcode() != null && "jsr".equals(inst.getCpuOpcode().getMnemonic())) {
                  String callee = inst.getOperand1();
                  if(callee != null && scanScope != null && !scanScope.isEmpty()) {
                     int dotIdx = callee.indexOf('.');
                     String calleeScope = (dotIdx != -1) ? callee.substring(0, dotIdx) : callee;
                     directCalls.computeIfAbsent(scanScope, k -> new HashSet<>()).add(calleeScope);
                  }
               }
            }
         }

         // Transitive closure of calls per scope
         Map<String, Set<String>> allCallees = new HashMap<>();
         for(String sc : scopeLinesMap.keySet()) {
            Set<String> visited = new HashSet<>();
            Queue<String> queue = new ArrayDeque<>();
            if(directCalls.containsKey(sc)) {
               queue.addAll(directCalls.get(sc));
            }
            while(!queue.isEmpty()) {
               String next = queue.poll();
               if(visited.add(next)) {
                  if(directCalls.containsKey(next)) {
                     queue.addAll(directCalls.get(next));
                  }
               }
            }
            allCallees.put(sc, visited);
         }

         for(Map.Entry<String, List<LineRef>> entry : scopeLinesMap.entrySet()) {
            List<LineRef> sLines = entry.getValue();

            // 1. Parse all .label declarations in this scope
            Map<String, String> labelToVal = new HashMap<>();
            Map<String, Set<String>> valToLabels = new HashMap<>();
            for(LineRef ref : sLines) {
               if(ref.line instanceof AsmLabelDecl) {
                  AsmLabelDecl decl = (AsmLabelDecl) ref.line;
                  String name = decl.getName().trim();
                  int dotIdx = name.lastIndexOf('.');
                  String simpleName = dotIdx != -1 ? name.substring(dotIdx + 1) : name;
                  String val = decl.getValue().trim();
                  labelToVal.put(simpleName, val);
                  labelToVal.put(name, val);
                  valToLabels.computeIfAbsent(val, k -> new HashSet<>()).add(simpleName);
               }
            }

            // Build protected physical addresses for this scope: global + own non-SSA + callees non-SSA
            Set<Integer> protectedAddrs = new HashSet<>(trulyGlobalPhysicalAddrs);
            if(scopeNamedPhysicalAddrs.containsKey(entry.getKey())) {
               protectedAddrs.addAll(scopeNamedPhysicalAddrs.get(entry.getKey()));
            }
            Set<String> callees = allCallees.get(entry.getKey());
            if(callees != null) {
               for(String callee : callees) {
                  if(scopeNamedPhysicalAddrs.containsKey(callee)) {
                     protectedAddrs.addAll(scopeNamedPhysicalAddrs.get(callee));
                  }
               }
            }

            // 2. Identify candidate procedure-local SSA temporaries:
            // Must be __\d+, must map to a raw hardware address ($xx or decimal),
            // must NOT collide with any global/parameter address, and must uniquely own that hardware address in this scope!
            Set<String> ssaCandidates = new HashSet<>();
            Map<String, String> candToAddress = new HashMap<>();

            for(Map.Entry<String, String> entryVal : labelToVal.entrySet()) {
               String name = entryVal.getKey();
               String val = entryVal.getValue();
               if(isSsaTemp(name)) {
                  Integer addr = parseAddress(val);
                  boolean isRawAddress = addr != null;
                  if(isRawAddress && !protectedAddrs.contains(addr) && valToLabels.containsKey(val) && valToLabels.get(val).size() == 1) {
                     ssaCandidates.add(name);
                     candToAddress.put(name, val.toLowerCase());
                  }
               }
            }

            if(ssaCandidates.isEmpty()) {
               continue;
            }

            // 2b. Transient SSA Temporary Forwarding:
            // When an SSA variable (or byte offset) v is written (sta v) and read exactly once (lda v; sta TARGET)
            // within the same basic block, forward TARGET into the original store and remove the copy.
            Set<String> allSsaVarsInScope = new HashSet<>(ssaCandidates);
            for(String cand : ssaCandidates) {
               allSsaVarsInScope.add(cand + "+1");
               allSsaVarsInScope.add(cand + "+2");
               allSsaVarsInScope.add(cand + "+3");
            }

            for(String v : allSsaVarsInScope) {
               List<Integer> readIndices = new ArrayList<>();
               List<Integer> writeIndices = new ArrayList<>();
               boolean hasComplexRef = false;

               for(int k = 0; k < sLines.size(); k++) {
                  AsmLine line = sLines.get(k).line;
                  if(line instanceof AsmInstruction) {
                     AsmInstruction inst = (AsmInstruction) line;
                     if(inst.getCpuOpcode() == null) continue;
                     if(isExactVarMatch(inst.getOperand1(), v)) {
                        String mnem = inst.getCpuOpcode().getMnemonic();
                        CpuAddressingMode m = inst.getCpuOpcode().getAddressingMode();
                        boolean isStore = (mnem.equals("sta") || mnem.equals("stx") || mnem.equals("sty")) &&
                                          (m.equals(CpuAddressingMode.ZP) || m.equals(CpuAddressingMode.ABS));
                        if(isStore && mnem.equals("sta")) {
                           writeIndices.add(k);
                        } else if(mnem.equals("lda") && (m.equals(CpuAddressingMode.ZP) || m.equals(CpuAddressingMode.ABS))) {
                           readIndices.add(k);
                        } else {
                           hasComplexRef = true;
                           break;
                        }
                     }
                  } else if(line instanceof AsmInlineKickAsm) {
                     if(((AsmInlineKickAsm) line).getAsm().contains(v)) {
                        hasComplexRef = true;
                        break;
                     }
                  }
               }

               if(!hasComplexRef && readIndices.size() == 1 && writeIndices.size() == 1) {
                  int wIdx = writeIndices.get(0);
                  int rIdx = readIndices.get(0);
                  if(wIdx < rIdx) {
                     int nextStoreIdx = -1;
                     for(int n = rIdx + 1; n < sLines.size(); n++) {
                        AsmLine nl = sLines.get(n).line;
                        if(nl instanceof AsmComment || nl instanceof AsmConstant || nl instanceof AsmLabelDecl) continue;
                        if(nl instanceof AsmInstruction) {
                           AsmInstruction nInst = (AsmInstruction) nl;
                           if(nInst.getCpuOpcode() != null && "sta".equals(nInst.getCpuOpcode().getMnemonic())) {
                              CpuAddressingMode nm = nInst.getCpuOpcode().getAddressingMode();
                              if(nm.equals(CpuAddressingMode.ZP) || nm.equals(CpuAddressingMode.ABS)) {
                                 nextStoreIdx = n;
                              }
                           }
                        }
                        break;
                     }

                     if(nextStoreIdx != -1) {
                        AsmInstruction origStore = (AsmInstruction) sLines.get(wIdx).line;
                        AsmInstruction readInst = (AsmInstruction) sLines.get(rIdx).line;
                        AsmInstruction finalStore = (AsmInstruction) sLines.get(nextStoreIdx).line;
                        String target = finalStore.getOperand1();

                        if(target != null && !Pass5RedundantStoreAndReloadElimination.isHardwareAddress(target) &&
                           !target.equals(v) && !origStore.isNoOptimize() && !readInst.isNoOptimize() && !finalStore.isNoOptimize()) {

                           boolean safe = true;
                           for(int m = wIdx + 1; m < rIdx; m++) {
                              AsmLine ml = sLines.get(m).line;
                              if(ml instanceof AsmLabel || ml instanceof AsmScopeBegin || ml instanceof AsmScopeEnd || ml instanceof AsmInlineKickAsm) {
                                 safe = false; break;
                              }
                              if(ml instanceof AsmInstruction) {
                                 AsmInstruction mi = (AsmInstruction) ml;
                                 if(mi.getCpuOpcode() == null) { safe = false; break; }
                                 if(mi.getCpuOpcode().isJump() || mi.getCpuOpcode().getMnemonic().equals("jsr")) {
                                    safe = false; break;
                                 }
                                 if(doesInstructionReferenceVar(mi, target) || doesInstructionReferenceVar(mi, v)) {
                                    safe = false; break;
                                 }
                              }
                           }

                           if(safe) {
                              getLog().append("Forwarding transient temporary " + v + " -> " + target);
                              origStore.setOperand1(target);
                              origStore.setCpuOpcode(finalStore.getCpuOpcode());
                              sLines.get(rIdx).chunk.getLines().remove(readInst);
                              sLines.get(nextStoreIdx).chunk.getLines().remove(finalStore);
                              passModified = true;
                              overallModified = true;
                              break;
                           }
                        }
                     }
                  }
               }
            }
            if(passModified) break;

            // 3. Scan scope for any READ / ALIAS / ADDRESS-OF / HARDWARE-ADDR references to candidates
            Set<String> readVars = new HashSet<>();
            for(LineRef ref : sLines) {
               if(ref.line instanceof AsmLabelDecl) {
                  AsmLabelDecl decl = (AsmLabelDecl) ref.line;
                  String val = decl.getValue();
                  for(String cand : ssaCandidates) {
                     if(val.contains(cand)) {
                        readVars.add(cand);
                     }
                  }
               } else if(ref.line instanceof AsmInlineKickAsm) {
                  String asm = ((AsmInlineKickAsm) ref.line).getAsm();
                  for(String cand : ssaCandidates) {
                     String hwAddr = candToAddress.get(cand);
                     if(asm.contains(cand) || (hwAddr != null && asm.toLowerCase().contains(hwAddr))) {
                        readVars.add(cand);
                     }
                  }
               } else if(ref.line instanceof AsmInstruction) {
                  AsmInstruction inst = (AsmInstruction) ref.line;
                  if(inst.getCpuOpcode() == null) continue;
                  String mnem = inst.getCpuOpcode().getMnemonic();
                  CpuAddressingMode mode = inst.getCpuOpcode().getAddressingMode();
                  String op = inst.getOperand1();
                  if(op == null) continue;

                  for(String cand : ssaCandidates) {
                     String hwAddr = candToAddress.get(cand);
                     boolean matchesCand = op.contains(cand);
                     boolean matchesHw = hwAddr != null && op.toLowerCase().contains(hwAddr);
                     if(matchesCand || matchesHw) {
                        boolean isDirectStore = (mnem.equals("sta") || mnem.equals("stx") || mnem.equals("sty")) &&
                                                (mode.equals(CpuAddressingMode.ZP) || mode.equals(CpuAddressingMode.ABS));
                        // If it's not a direct store to cand (or if mode is indirect like (cand),y or address-of #<cand), it's a read!
                        if(!isDirectStore || op.contains("(") || op.contains("#") || matchesHw) {
                           readVars.add(cand);
                        }
                     }
                  }
               }
            }

            // Dead candidates are those never read
            Set<String> deadVars = new HashSet<>(ssaCandidates);
            deadVars.removeAll(readVars);

            if(deadVars.isEmpty()) {
               continue;
            }

            // 3. Find and eliminate dead stores
            for(int i = 0; i < sLines.size(); i++) {
               LineRef ref = sLines.get(i);
               if(!(ref.line instanceof AsmInstruction)) continue;

               AsmInstruction inst = (AsmInstruction) ref.line;
               if(inst.isNoOptimize() || inst.getCpuOpcode() == null) continue;

               String mnem = inst.getCpuOpcode().getMnemonic();
               CpuAddressingMode mode = inst.getCpuOpcode().getAddressingMode();
               String op = inst.getOperand1();

               boolean isStore = (mnem.equals("sta") || mnem.equals("stx") || mnem.equals("sty")) &&
                                 (mode.equals(CpuAddressingMode.ZP) || mode.equals(CpuAddressingMode.ABS));

               if(!isStore || op == null) continue;

               String baseVar = getBaseVar(op);
               int dotIdx = baseVar.lastIndexOf('.');
               String simpleName = dotIdx != -1 ? baseVar.substring(dotIdx + 1) : baseVar;

               if(deadVars.contains(baseVar) || deadVars.contains(simpleName)) {
                  // Found a dead store!
                  String reg = mnem.equals("sta") ? "A" : (mnem.equals("stx") ? "X" : "Y");

                  // Check if previous instruction is an immediate load into the same register
                  // and the next instruction overwrites that register without reading it
                  int prevIdx = -1;
                  for(int p = i - 1; p >= 0; p--) {
                     AsmLine pLine = sLines.get(p).line;
                     if(pLine instanceof AsmComment || pLine instanceof AsmConstant || pLine instanceof AsmLabelDecl) continue;
                     if(pLine instanceof AsmInstruction) {
                        prevIdx = p;
                     }
                     break;
                  }

                  int nextIdx = -1;
                  for(int n = i + 1; n < sLines.size(); n++) {
                     AsmLine nLine = sLines.get(n).line;
                     if(nLine instanceof AsmComment || nLine instanceof AsmConstant || nLine instanceof AsmLabelDecl) continue;
                     if(nLine instanceof AsmInstruction) {
                        nextIdx = n;
                     }
                     break;
                  }

                  boolean canEliminatePrevLoad = false;
                  if(prevIdx != -1 && nextIdx != -1) {
                     AsmInstruction prevInst = (AsmInstruction) sLines.get(prevIdx).line;
                     AsmInstruction nextInst = (AsmInstruction) sLines.get(nextIdx).line;
                     if(prevInst.getCpuOpcode() != null && nextInst.getCpuOpcode() != null) {
                        String prevMnem = prevInst.getCpuOpcode().getMnemonic();
                        CpuAddressingMode prevMode = prevInst.getCpuOpcode().getAddressingMode();
                        boolean isMatchingImmLoad = (reg.equals("A") && prevMnem.equals("lda") && prevMode.equals(CpuAddressingMode.IMM)) ||
                                                   (reg.equals("X") && prevMnem.equals("ldx") && prevMode.equals(CpuAddressingMode.IMM)) ||
                                                   (reg.equals("Y") && prevMnem.equals("ldy") && prevMode.equals(CpuAddressingMode.IMM));

                        if(isMatchingImmLoad && overwritesRegister(nextInst, reg)) {
                           canEliminatePrevLoad = true;
                        }
                     }
                  }

                  getLog().append("Eliminating dead store " + inst.getAsm() + " to dead local variable " + op);
                  ref.chunk.getLines().remove(inst);

                  if(canEliminatePrevLoad && prevIdx != -1) {
                     LineRef prevRef = sLines.get(prevIdx);
                     AsmInstruction prevInst = (AsmInstruction) prevRef.line;
                     getLog().append("Eliminating dead preceding load " + prevInst.getAsm());
                     prevRef.chunk.getLines().remove(prevInst);
                  }

                  passModified = true;
                  overallModified = true;
                  break;
               }
            }

            if(passModified) {
               break;
            }
         }
      }

      return overallModified;
   }
}
