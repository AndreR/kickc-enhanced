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

   private static boolean doesInstructionModifyA(AsmInstruction inst) {
      if(inst == null || inst.getCpuOpcode() == null) return false;
      String mnem = inst.getCpuOpcode().getMnemonic();
      CpuAddressingMode mode = inst.getCpuOpcode().getAddressingMode();
      return mnem.equals("lda") || mnem.equals("pla") || mnem.equals("txa") || mnem.equals("tya") ||
             mnem.equals("adc") || mnem.equals("sbc") || mnem.equals("and") || mnem.equals("ora") ||
             mnem.equals("eor") || mnem.equals("lax") || mnem.equals("alr") || mnem.equals("anc") ||
             mnem.equals("arr") || mnem.equals("axs") || mnem.equals("dcp") || mnem.equals("isc") ||
             mnem.equals("rla") || mnem.equals("rra") || mnem.equals("slo") || mnem.equals("sre") ||
             ((mnem.equals("asl") || mnem.equals("lsr") || mnem.equals("rol") || mnem.equals("ror")) && mode.equals(CpuAddressingMode.NON));
   }

   public static class CanonicalResolver {
      private final Map<String, String> aliasMap = new HashMap<>();

      public CanonicalResolver() {}

      public CanonicalResolver(Map<String, String> initialAliases) {
         if(initialAliases != null) {
            aliasMap.putAll(initialAliases);
         }
      }

      public void addAlias(String name, String value) {
         if(name == null || value == null) return;
         String n = name.trim();
         String v = value.trim();
         if(v.startsWith(".z ")) v = v.substring(3).trim();
         aliasMap.put(n, v);
         int dotIdx = n.lastIndexOf('.');
         if(dotIdx != -1) {
            aliasMap.put(n.substring(dotIdx + 1), v);
         }
      }

      public String resolve(String operand) {
         if(operand == null) return "";
         String op = operand.trim();
         if(op.startsWith(".z ")) op = op.substring(3).trim();

         // Extract base and initial offset
         String base = op;
         int offset = 0;
         int plusIdx = base.indexOf('+');
         int minusIdx = base.indexOf('-');
         if(plusIdx != -1) {
            try {
               offset = Integer.parseInt(base.substring(plusIdx + 1).trim());
               base = base.substring(0, plusIdx).trim();
            } catch(Exception ignored) {}
         } else if(minusIdx != -1) {
            try {
               offset = -Integer.parseInt(base.substring(minusIdx + 1).trim());
               base = base.substring(0, minusIdx).trim();
            } catch(Exception ignored) {}
         }

         // Follow alias chains
         Set<String> visited = new HashSet<>();
         visited.add(base);
         for(int i = 0; i < 20; i++) {
            String next = aliasMap.get(base);
            if(next == null) {
               int dotIdx = base.lastIndexOf('.');
               if(dotIdx != -1) {
                  next = aliasMap.get(base.substring(dotIdx + 1));
               }
            }
            if(next == null) break;
            if(next.startsWith(".z ")) next = next.substring(3).trim();

            int nPlus = next.indexOf('+');
            int nMinus = next.indexOf('-');
            if(nPlus != -1) {
               try {
                  offset += Integer.parseInt(next.substring(nPlus + 1).trim());
                  base = next.substring(0, nPlus).trim();
               } catch(Exception e) {
                  base = next;
               }
            } else if(nMinus != -1) {
               try {
                  offset -= Integer.parseInt(next.substring(nMinus + 1).trim());
                  base = next.substring(0, nMinus).trim();
               } catch(Exception e) {
                  base = next;
               }
            } else {
               base = next;
            }

            if(!visited.add(base)) break; // cycle protection
         }

         // If base is a numeric hardware address, fold the offset into the address
         Integer addr = parseAddress(base);
         if(addr != null) {
            int finalAddr = addr + offset;
            if(finalAddr >= 0 && finalAddr <= 0xff) {
               return String.format("$%02x", finalAddr);
            } else {
               return String.format("$%04x", finalAddr & 0xffff);
            }
         }

         if(offset != 0) {
            return base + (offset > 0 ? "+" + offset : "" + offset);
         }
         return base;
      }
   }

   private static List<String> extractReferencedTokens(String operand) {
      List<String> tokens = new ArrayList<>();
      if(operand == null) return tokens;
      String op = operand.trim();
      if(op.startsWith(".z ")) op = op.substring(3).trim();
      tokens.add(op);

      // Handle indirect addressing: ($fe),y or ($33,x) or ($fe)
      int openParen = op.indexOf('(');
      int closeParen = op.indexOf(')');
      if(openParen != -1 && closeParen != -1 && closeParen > openParen) {
         String inner = op.substring(openParen + 1, closeParen).trim();
         int comma = inner.indexOf(',');
         if(comma != -1) inner = inner.substring(0, comma).trim();
         if(!inner.isEmpty()) tokens.add(inner);
      }

      // Handle indexed addressing: array,x or array+1,y
      int comma = op.indexOf(',');
      if(comma != -1) {
         String beforeComma = op.substring(0, comma).trim();
         if(!beforeComma.isEmpty()) tokens.add(beforeComma);
      }

      // Handle immediate address-of: #<sym, #>sym, #sym
      if(op.startsWith("#<") || op.startsWith("#>")) {
         String after = op.substring(2).trim();
         if(!after.isEmpty()) tokens.add(after);
      } else if(op.startsWith("#")) {
         String after = op.substring(1).trim();
         if(!after.isEmpty()) tokens.add(after);
      }

      return tokens;
   }

   private static boolean isVariableRead(String var, CanonicalResolver resolver, Set<String> allReadKeysInScope, List<String> inlineKickAsmList) {
      if(var == null) return false;
      String v = var.trim();
      if(v.startsWith(".z ")) v = v.substring(3).trim();
      String canon = resolver.resolve(v);
      String base = getBaseVar(v);
      String canonBase = resolver.resolve(base);

      if(allReadKeysInScope.contains(v) || (canon != null && allReadKeysInScope.contains(canon)) ||
         allReadKeysInScope.contains(base) || (canonBase != null && allReadKeysInScope.contains(canonBase))) {
         return true;
      }
      for(String inlineAsm : inlineKickAsmList) {
         if(inlineAsm.contains(v) || (canon != null && inlineAsm.contains(canon)) ||
            inlineAsm.contains(base) || (canonBase != null && inlineAsm.contains(canonBase))) {
            return true;
         }
      }
      return false;
   }

   private static boolean readsRegisterA(AsmInstruction inst) {
      if(inst == null || inst.getCpuOpcode() == null) return true;
      String mnem = inst.getCpuOpcode().getMnemonic();
      CpuAddressingMode mode = inst.getCpuOpcode().getAddressingMode();
      if(mnem.equals("sta") || mnem.equals("sax") || mnem.equals("pha") ||
         mnem.equals("tax") || mnem.equals("tay") || mnem.equals("cmp") ||
         mnem.equals("adc") || mnem.equals("sbc") || mnem.equals("and") ||
         mnem.equals("ora") || mnem.equals("eor") || mnem.equals("bit") ||
         mnem.equals("alr") || mnem.equals("arr") || mnem.equals("anc") ||
         mnem.equals("axs") || mnem.equals("jsr") || mnem.equals("rts") ||
         mnem.equals("rti")) {
         return true;
      }
      if((mnem.equals("asl") || mnem.equals("lsr") || mnem.equals("rol") || mnem.equals("ror")) &&
         (mode.equals(CpuAddressingMode.NON) || "a".equalsIgnoreCase(inst.getOperand1()))) {
         return true;
      }
      return false;
   }

   private static boolean overwritesRegisterA(AsmInstruction inst) {
      if(inst == null || inst.getCpuOpcode() == null) return false;
      String mnem = inst.getCpuOpcode().getMnemonic();
      return mnem.equals("lda") || mnem.equals("pla") || mnem.equals("lax") ||
             mnem.equals("txa") || mnem.equals("tya");
   }

   private static boolean overwritesRegister(AsmInstruction inst, String reg) {
      if(inst == null || inst.getCpuOpcode() == null) return false;
      String mnem = inst.getCpuOpcode().getMnemonic();
      if(reg.equals("A")) {
         return overwritesRegisterA(inst);
      } else if(reg.equals("X")) {
         return mnem.equals("ldx") || mnem.equals("lax") || mnem.equals("tax") || mnem.equals("tsx");
      } else if(reg.equals("Y")) {
         return mnem.equals("ldy") || mnem.equals("tay");
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
         Map<String, String> globalAliases = new HashMap<>();

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
               String val = decl.getValue().trim();
               if(scanScope == null || scanScope.isEmpty()) {
                  globalAliases.put(name, val);
                  int dot = name.lastIndexOf('.');
                  if(dot != -1) globalAliases.put(name.substring(dot + 1), val);
               }
               if(!isSsaTemp(name)) {
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
            CanonicalResolver resolver = new CanonicalResolver(globalAliases);
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
                  resolver.addAlias(name, val);
                  resolver.addAlias(simpleName, val);
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

            // 2b. Transient SSA Temporary Forwarding:
            // When an SSA variable (or byte offset) v is written (sta v) and read exactly once (lda v; sta TARGET)
            // within the same basic block, forward TARGET into the original store and remove the copy.
            if(!ssaCandidates.isEmpty()) {
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
                                    if(doesInstructionModifyA(mi)) {
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
                  } else if(!hasComplexRef && writeIndices.size() == 2 && readIndices.size() == 2) {
                     int w0 = writeIndices.get(0);
                     int w1 = writeIndices.get(1);
                     int r0 = readIndices.get(0);
                     int r1 = readIndices.get(1);
                     if(w0 < w1 && w1 < r0 && r0 < r1) {
                        AsmInstruction w0Inst = (AsmInstruction) sLines.get(w0).line;
                        AsmInstruction w1Inst = (AsmInstruction) sLines.get(w1).line;
                        AsmInstruction r0Inst = (AsmInstruction) sLines.get(r0).line;
                        AsmInstruction r1Inst = (AsmInstruction) sLines.get(r1).line;
                        String w0Op = w0Inst.getOperand1();
                        String w1Op = w1Inst.getOperand1();
                        String r0Op = r0Inst.getOperand1();
                        String r1Op = r1Inst.getOperand1();
                        if(w0Op != null && w1Op != null && r0Op != null && r1Op != null &&
                           w0Op.equals(v) && w1Op.equals(v + "+1") && r0Op.equals(v) && r1Op.equals(v + "+1")) {
                           int s0 = -1;
                           for(int n = r0 + 1; n < r1; n++) {
                              AsmLine nl = sLines.get(n).line;
                              if(nl instanceof AsmComment || nl instanceof AsmConstant || nl instanceof AsmLabelDecl) continue;
                              if(nl instanceof AsmInstruction) {
                                 AsmInstruction ni = (AsmInstruction) nl;
                                 if(ni.getCpuOpcode() != null && "sta".equals(ni.getCpuOpcode().getMnemonic())) s0 = n;
                              }
                              break;
                           }
                           int s1 = -1;
                           for(int n = r1 + 1; n < sLines.size(); n++) {
                              AsmLine nl = sLines.get(n).line;
                              if(nl instanceof AsmComment || nl instanceof AsmConstant || nl instanceof AsmLabelDecl) continue;
                              if(nl instanceof AsmInstruction) {
                                 AsmInstruction ni = (AsmInstruction) nl;
                                 if(ni.getCpuOpcode() != null && "sta".equals(ni.getCpuOpcode().getMnemonic())) s1 = n;
                              }
                              break;
                           }
                           if(s0 != -1 && s1 != -1) {
                              AsmInstruction s0Inst = (AsmInstruction) sLines.get(s0).line;
                              AsmInstruction s1Inst = (AsmInstruction) sLines.get(s1).line;
                              String target0 = s0Inst.getOperand1();
                              String target1 = s1Inst.getOperand1();
                              if(target0 != null && target1 != null && target1.equals(target0 + "+1") &&
                                 !Pass5RedundantStoreAndReloadElimination.isHardwareAddress(target0)) {
                                 boolean safe = true;
                                 for(int m = w0 + 1; m < r0; m++) {
                                    AsmLine ml = sLines.get(m).line;
                                    if(ml instanceof AsmLabel || ml instanceof AsmScopeBegin || ml instanceof AsmScopeEnd || ml instanceof AsmInlineKickAsm) {
                                       safe = false; break;
                                    }
                                    if(ml instanceof AsmInstruction) {
                                       AsmInstruction mi = (AsmInstruction) ml;
                                       if(mi.getCpuOpcode() == null || mi.getCpuOpcode().isJump() || mi.getCpuOpcode().getMnemonic().equals("jsr")) {
                                          safe = false; break;
                                       }
                                       if(doesInstructionReferenceVar(mi, target0) || doesInstructionReferenceVar(mi, target1)) {
                                          safe = false; break;
                                       }
                                    }
                                 }
                                 if(safe) {
                                    getLog().append("Forwarding 16-bit transient temporary " + v + " -> " + target0);
                                    w0Inst.setOperand1(target0);
                                    w0Inst.setCpuOpcode(s0Inst.getCpuOpcode());
                                    w1Inst.setOperand1(target1);
                                    w1Inst.setCpuOpcode(s1Inst.getCpuOpcode());
                                    sLines.get(r0).chunk.getLines().remove(r0Inst);
                                    sLines.get(s0).chunk.getLines().remove(s0Inst);
                                    sLines.get(r1).chunk.getLines().remove(r1Inst);
                                    sLines.get(s1).chunk.getLines().remove(s1Inst);
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
            if(passModified) break;

            // 2c. Scope Read Collection: Pre-collect all read symbols and canonical keys in O(N)
            Set<String> allReadKeysInScope = new HashSet<>();
            List<String> inlineKickAsmList = new ArrayList<>();
            for(LineRef rl : sLines) {
               if(rl.line instanceof AsmInstruction) {
                  AsmInstruction ri = (AsmInstruction) rl.line;
                  if(ri.getCpuOpcode() != null) {
                     String rm = ri.getCpuOpcode().getMnemonic();
                     CpuAddressingMode rmode = ri.getCpuOpcode().getAddressingMode();
                     boolean isDirectStore = (rm.equals("sta") || rm.equals("stx") || rm.equals("sty") || rm.equals("sax")) &&
                                             (rmode.equals(CpuAddressingMode.ZP) || rmode.equals(CpuAddressingMode.ABS));
                     if(!isDirectStore) {
                        String op = ri.getOperand1();
                        if(op != null) {
                           for(String token : extractReferencedTokens(op)) {
                              allReadKeysInScope.add(token);
                              String canon = resolver.resolve(token);
                              if(canon != null && !canon.isEmpty()) allReadKeysInScope.add(canon);
                              String base = getBaseVar(token);
                              allReadKeysInScope.add(base);
                              String canonBase = resolver.resolve(base);
                              if(canonBase != null && !canonBase.isEmpty()) allReadKeysInScope.add(canonBase);
                           }
                        }
                     }
                  }
               } else if(rl.line instanceof AsmInlineKickAsm) {
                  inlineKickAsmList.add(((AsmInlineKickAsm) rl.line).getAsm());
               } else if(rl.line instanceof AsmLabelDecl) {
                  String val = ((AsmLabelDecl) rl.line).getValue();
                  if(val != null) {
                     for(String token : extractReferencedTokens(val)) {
                        allReadKeysInScope.add(token);
                        String canon = resolver.resolve(token);
                        if(canon != null && !canon.isEmpty()) allReadKeysInScope.add(canon);
                     }
                  }
               }
            }

            // 2c. Dead Load-and-Store Pair Elimination:
            // Eliminates dead stores from function returns or SSA temps where the stored
            // variable/offset is NEVER read in the procedure scope (directly or via any alias),
            // and optionally eliminates the preceding load if register A is cleanly overwritten
            // without being read by any instruction or branch.
            for(int k = 0; k < sLines.size(); k++) {
               AsmLine l1 = sLines.get(k).line;
               if(!(l1 instanceof AsmInstruction)) continue;
               AsmInstruction inst1 = (AsmInstruction) l1;
               if(inst1.isNoOptimize() || inst1.getCpuOpcode() == null) continue;
               if(!("lda".equals(inst1.getCpuOpcode().getMnemonic()))) continue;

               int nextStoreIdx = -1;
               for(int n = k + 1; n < sLines.size(); n++) {
                  AsmLine nl = sLines.get(n).line;
                  if(nl instanceof AsmComment || nl instanceof AsmConstant || nl instanceof AsmLabelDecl) continue;
                  if(nl instanceof AsmInstruction) {
                     AsmInstruction ni = (AsmInstruction) nl;
                     if(ni.getCpuOpcode() != null && "sta".equals(ni.getCpuOpcode().getMnemonic())) {
                        CpuAddressingMode nm = ni.getCpuOpcode().getAddressingMode();
                        if(nm.equals(CpuAddressingMode.ZP) || nm.equals(CpuAddressingMode.ABS)) {
                           nextStoreIdx = n;
                        }
                     }
                  }
                  break;
               }

               if(nextStoreIdx != -1) {
                  AsmInstruction inst2 = (AsmInstruction) sLines.get(nextStoreIdx).line;
                  String destOp = inst2.getOperand1();
                  if(destOp != null && isSsaTemp(destOp) && !Pass5RedundantStoreAndReloadElimination.isHardwareAddress(destOp)) {
                     String baseDest = getBaseVar(destOp);
                     int dotIdx = baseDest.lastIndexOf('.');
                     String simpleDest = (dotIdx != -1) ? baseDest.substring(dotIdx + 1) : baseDest;

                     if(!labelToVal.containsKey(baseDest) && !labelToVal.containsKey(simpleDest)) {
                        continue;
                     }

                     String canonicalDest = resolver.resolve(destOp);
                     Integer destAddr = parseAddress(canonicalDest);
                     if(destAddr != null && protectedAddrs.contains(destAddr)) {
                        continue;
                     }

                     boolean isRead = isVariableRead(destOp, resolver, allReadKeysInScope, inlineKickAsmList);

                     if(!isRead) {
                        boolean canEliminateLda = false;
                        boolean flagsLive = true;

                        for(int f = nextStoreIdx + 1; f < sLines.size(); f++) {
                           AsmLine fl = sLines.get(f).line;
                           if(fl instanceof AsmComment || fl instanceof AsmConstant || fl instanceof AsmLabelDecl) continue;
                           if(fl instanceof AsmLabel || fl instanceof AsmScopeBegin || fl instanceof AsmScopeEnd || fl instanceof AsmInlineKickAsm) {
                              break;
                           }
                           if(fl instanceof AsmInstruction) {
                              AsmInstruction fi = (AsmInstruction) fl;
                              if(fi.getCpuOpcode() == null || fi.getCpuOpcode().isJump()) break;
                              String fm = fi.getCpuOpcode().getMnemonic();
                              if(fm.startsWith("b")) {
                                 if(flagsLive) break;
                              }
                              if(readsRegisterA(fi)) {
                                 break;
                              }
                              if(overwritesRegisterA(fi)) {
                                 canEliminateLda = true;
                                 break;
                              }
                              if(fi.getCpuOpcode().getClobber().isFlagZ() || fi.getCpuOpcode().getClobber().isFlagN()) {
                                 flagsLive = false;
                              }
                           }
                        }

                        if(canEliminateLda) {
                           getLog().append("Eliminating dead load-and-store pair: " + inst1.getAsm() + " ; " + inst2.getAsm());
                           sLines.get(k).chunk.getLines().remove(inst1);
                           sLines.get(nextStoreIdx).chunk.getLines().remove(inst2);
                           passModified = true;
                           overallModified = true;
                           k = nextStoreIdx;
                        } else {
                           getLog().append("Eliminating dead store to dead temp: " + inst2.getAsm() + " (preserving load for subsequent A reads)");
                           sLines.get(nextStoreIdx).chunk.getLines().remove(inst2);
                           passModified = true;
                           overallModified = true;
                           k = nextStoreIdx;
                        }
                     }
                  }
               }
            }
            if(passModified) break;

            // 3. Scan scope for any READ / ALIAS / ADDRESS-OF / HARDWARE-ADDR references to candidates
            if(!ssaCandidates.isEmpty()) {
               Set<String> readVars = new HashSet<>();
               for(String cand : ssaCandidates) {
                  if(isVariableRead(cand, resolver, allReadKeysInScope, inlineKickAsmList)) {
                     readVars.add(cand);
                  }
               }

               // Dead candidates are those never read
               Set<String> deadVars = new HashSet<>(ssaCandidates);
               deadVars.removeAll(readVars);

               if(!deadVars.isEmpty()) {
                  // Find and eliminate dead stores
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
                        String reg = mnem.equals("sta") ? "A" : (mnem.equals("stx") ? "X" : "Y");

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
                              boolean isMatchingLoad = (reg.equals("A") && prevMnem.equals("lda") && (prevMode.equals(CpuAddressingMode.IMM) || prevMode.equals(CpuAddressingMode.ZP) || prevMode.equals(CpuAddressingMode.ABS))) ||
                                                       (reg.equals("X") && prevMnem.equals("ldx") && (prevMode.equals(CpuAddressingMode.IMM) || prevMode.equals(CpuAddressingMode.ZP) || prevMode.equals(CpuAddressingMode.ABS))) ||
                                                       (reg.equals("Y") && prevMnem.equals("ldy") && (prevMode.equals(CpuAddressingMode.IMM) || prevMode.equals(CpuAddressingMode.ZP) || prevMode.equals(CpuAddressingMode.ABS)));

                              if(isMatchingLoad && !Pass5RedundantStoreAndReloadElimination.isHardwareAddress(prevInst.getOperand1()) && overwritesRegister(nextInst, reg)) {
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
