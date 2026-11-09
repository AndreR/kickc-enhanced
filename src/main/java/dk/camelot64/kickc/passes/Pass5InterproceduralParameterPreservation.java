package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;

import java.util.*;

/**
 * Pass 5 Optimization: Interprocedural Preservation of Unmodified Zero-Page Parameters across JSR.
 *
 * When a caller calls a subroutine (JSR proc), many zero-page parameters and variables remain
 * completely unmodified by the callee (read-only parameters, or unrelated zero-page locations).
 *
 * This pass performs call-graph transitive closure analysis over all procedures in AsmProgram:
 * 1. Computes the exact set of zero-page addresses modified by each procedure (and all its transitive callees).
 * 2. Tracks known immediate values in zero-page locations across JSR calls.
 * 3. Eliminates subsequent redundant stores (STA.Z, STX.Z, STY.Z) if the destination zero-page location
 *    is guaranteed to already contain the identical immediate value.
 */
public class Pass5InterproceduralParameterPreservation extends Pass5AsmOptimization {

   private static final Set<String> ZP_MODIFY_MNEMONICS = new HashSet<>(Arrays.asList(
         "sta", "stx", "sty", "sax", "asl", "lsr", "rol", "ror", "inc", "dec", "dcp", "isc"
   ));

   public Pass5InterproceduralParameterPreservation(Program program) {
      super(program);
   }

   private static class ProcedureInfo {
      final String name;
      final Set<String> directZpWrites = new HashSet<>();
      final Set<String> callees = new HashSet<>();
      boolean hasUnknownWrites = false;

      ProcedureInfo(String name) {
         this.name = name;
      }
   }

   public static boolean readsRegisterA(AsmInstruction inst) {
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

   public static boolean overwritesRegisterA(AsmInstruction inst) {
      if(inst == null || inst.getCpuOpcode() == null) return false;
      String mnem = inst.getCpuOpcode().getMnemonic();
      return mnem.equals("lda") || mnem.equals("pla") || mnem.equals("lax") ||
             mnem.equals("txa") || mnem.equals("tya");
   }

   public static boolean readsRegisterX(AsmInstruction inst) {
      if(inst == null || inst.getCpuOpcode() == null) return true;
      String mnem = inst.getCpuOpcode().getMnemonic();
      CpuAddressingMode mode = inst.getCpuOpcode().getAddressingMode();
      if(mode.equals(CpuAddressingMode.ZPX) || mode.equals(CpuAddressingMode.ABX) ||
         mode.equals(CpuAddressingMode.IZX) || mode.equals(CpuAddressingMode.IAX)) {
         return true;
      }
      return mnem.equals("stx") || mnem.equals("sax") || mnem.equals("txa") ||
             mnem.equals("txs") || mnem.equals("cpx") || mnem.equals("inx") ||
             mnem.equals("dex") || mnem.equals("axs") || mnem.equals("jsr") ||
             mnem.equals("rts") || mnem.equals("rti");
   }

   public static boolean overwritesRegisterX(AsmInstruction inst) {
      if(inst == null || inst.getCpuOpcode() == null) return false;
      String mnem = inst.getCpuOpcode().getMnemonic();
      return mnem.equals("ldx") || mnem.equals("lax") || mnem.equals("tax") || mnem.equals("tsx");
   }

   public static boolean readsRegisterY(AsmInstruction inst) {
      if(inst == null || inst.getCpuOpcode() == null) return true;
      String mnem = inst.getCpuOpcode().getMnemonic();
      CpuAddressingMode mode = inst.getCpuOpcode().getAddressingMode();
      if(mode.equals(CpuAddressingMode.ZPY) || mode.equals(CpuAddressingMode.ABY) ||
         mode.equals(CpuAddressingMode.IZY)) {
         return true;
      }
      return mnem.equals("sty") || mnem.equals("tya") || mnem.equals("cpy") ||
             mnem.equals("iny") || mnem.equals("dey") || mnem.equals("jsr") ||
             mnem.equals("rts") || mnem.equals("rti");
   }

   public static boolean overwritesRegisterY(AsmInstruction inst) {
      if(inst == null || inst.getCpuOpcode() == null) return false;
      String mnem = inst.getCpuOpcode().getMnemonic();
      return mnem.equals("ldy") || mnem.equals("tay");
   }

   private boolean canEliminatePrecedingLoad(List<LineRef> allLines, int storeIdx, LineRef loadRef, String reg) {
      if(loadRef == null || !(loadRef.line instanceof AsmInstruction)) return false;
      if(!loadRef.chunk.getLines().contains(loadRef.line)) return false;

      int loadIdx = -1;
      for(int i = storeIdx - 1; i >= 0 && i >= storeIdx - 15; i--) {
         if(allLines.get(i) == loadRef) {
            loadIdx = i;
            break;
         }
      }
      if(loadIdx == -1) return false;

      for(int i = loadIdx + 1; i < storeIdx; i++) {
         AsmLine l = allLines.get(i).line;
         if(l instanceof AsmComment || l instanceof AsmConstant || l instanceof AsmLabelDecl) continue;
         if(!(l instanceof AsmInstruction)) return false;
         AsmInstruction inst = (AsmInstruction) l;
         if("A".equals(reg) && readsRegisterA(inst)) return false;
         if("X".equals(reg) && readsRegisterX(inst)) return false;
         if("Y".equals(reg) && readsRegisterY(inst)) return false;
      }

      for(int i = storeIdx + 1; i < allLines.size(); i++) {
         AsmLine l = allLines.get(i).line;
         if(l instanceof AsmComment || l instanceof AsmConstant || l instanceof AsmLabelDecl) continue;
         if(l instanceof AsmLabel || l instanceof AsmScopeBegin || l instanceof AsmScopeEnd || l instanceof AsmInlineKickAsm) {
            return false;
         }
         if(l instanceof AsmInstruction) {
            AsmInstruction inst = (AsmInstruction) l;
            if(inst.getCpuOpcode() == null || inst.getCpuOpcode().isJump()) return false;
            String m = inst.getCpuOpcode().getMnemonic();
            if(m.startsWith("b")) return false; // Conditional branch depends on flags

            if("A".equals(reg)) {
               if(readsRegisterA(inst)) return false;
               if(overwritesRegisterA(inst)) return true;
            } else if("X".equals(reg)) {
               if(readsRegisterX(inst)) return false;
               if(overwritesRegisterX(inst)) return true;
            } else if("Y".equals(reg)) {
               if(readsRegisterY(inst)) return false;
               if(overwritesRegisterY(inst)) return true;
            }
         }
      }
      return false;
   }

   @Override
   public boolean optimize() {
      boolean modified = false;

      // 0. Collect alias mappings across scopes
      Map<String, Map<String, String>> scopeAliasMap = new HashMap<>();
      Map<String, String> globalAliasMap = new HashMap<>();
      String scanScope = "";

      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         for(AsmLine line : chunk.getLines()) {
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

      // 1. Collect all procedures and their direct writes & callees
      Map<String, ProcedureInfo> procMap = new HashMap<>();
      ProcedureInfo currentProc = null;

      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         for(AsmLine line : chunk.getLines()) {
            if(line instanceof AsmScopeBegin) {
               String name = ((AsmScopeBegin) line).getLabel();
               currentProc = new ProcedureInfo(name);
               procMap.put(name, currentProc);
            } else if(line instanceof AsmScopeEnd) {
               currentProc = null;
            } else if(currentProc != null) {
               if(line instanceof AsmInlineKickAsm) {
                  currentProc.hasUnknownWrites = true;
               } else if(line instanceof AsmInstruction) {
                  AsmInstruction inst = (AsmInstruction) line;
                  CpuOpcode opcode = inst.getCpuOpcode();
                  if(opcode != null) {
                     String mnem = opcode.getMnemonic();
                     CpuAddressingMode mode = opcode.getAddressingMode();
                     if("jsr".equals(mnem)) {
                        String target = inst.getOperand1();
                        if(target != null && !target.startsWith("!")) {
                           currentProc.callees.add(target.trim());
                        } else {
                           currentProc.hasUnknownWrites = true;
                        }
                     } else if(ZP_MODIFY_MNEMONICS.contains(mnem)) {
                        if(mode == CpuAddressingMode.ZP || mode == CpuAddressingMode.ZPX || mode == CpuAddressingMode.ZPY) {
                           String op = inst.getOperand1();
                           if(op != null) {
                              Map<String, String> currentScopeAliases = (currentProc != null) ? scopeAliasMap.get(currentProc.name) : null;
                              String canon = Pass5RedundantStoreAndReloadElimination.getCanonical(op, currentScopeAliases, globalAliasMap);
                              if(canon != null && !canon.isEmpty()) {
                                 currentProc.directZpWrites.add(canon);
                              }
                              currentProc.directZpWrites.add(op.trim());
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      // 2. Transitive closure fixpoint of writes across call graph
      Map<String, Set<String>> totalZpWrites = new HashMap<>();
      Map<String, Boolean> totalUnknownWrites = new HashMap<>();

      for(Map.Entry<String, ProcedureInfo> entry : procMap.entrySet()) {
         totalZpWrites.put(entry.getKey(), new HashSet<>(entry.getValue().directZpWrites));
         totalUnknownWrites.put(entry.getKey(), entry.getValue().hasUnknownWrites);
      }

      boolean changed = true;
      while(changed) {
         changed = false;
         for(Map.Entry<String, ProcedureInfo> entry : procMap.entrySet()) {
            String procName = entry.getKey();
            Set<String> writes = totalZpWrites.get(procName);
            boolean unknown = totalUnknownWrites.get(procName);

            for(String callee : entry.getValue().callees) {
               ProcedureInfo calleeInfo = procMap.get(callee);
               if(calleeInfo != null) {
                  if(Boolean.TRUE.equals(totalUnknownWrites.get(callee)) && !unknown) {
                     totalUnknownWrites.put(procName, true);
                     unknown = true;
                     changed = true;
                  }
                  Set<String> calleeWrites = totalZpWrites.get(callee);
                  if(calleeWrites != null) {
                     int before = writes.size();
                     writes.addAll(calleeWrites);
                     if(writes.size() > before) {
                        changed = true;
                     }
                  }
               } else {
                  // Unknown external callee
                  if(!unknown) {
                     totalUnknownWrites.put(procName, true);
                     unknown = true;
                     changed = true;
                  }
               }
            }
         }
      }

      // 2b. Collect all jump targets in program (labels that are actually branched/jumped to)
      Set<String> jumpTargets = new HashSet<>();
      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         for(AsmLine line : chunk.getLines()) {
            if(line instanceof AsmInstruction) {
               AsmInstruction inst = (AsmInstruction) line;
               if(inst.getCpuOpcode() != null) {
                  String m = inst.getCpuOpcode().getMnemonic();
                  if(inst.getCpuOpcode().isJump() && !"jsr".equals(m) && !"rts".equals(m) && !"rti".equals(m)) {
                     String target = inst.getOperand1();
                     if(target != null) {
                        String t = target.trim();
                        jumpTargets.add(t);
                        while(t.startsWith("!")) t = t.substring(1).trim();
                        while(t.endsWith("+") || t.endsWith("-")) t = t.substring(0, t.length() - 1).trim();
                        jumpTargets.add(t);
                     }
                  }
               }
            }
         }
      }

      // 3. Scan lines across all chunks for redundant stores across JSR.
      // Notice: tracking persists across chunk boundaries within a procedure scope!
      List<LineRef> allLines = new ArrayList<>();
      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         for(AsmLine line : chunk.getLines()) {
            allLines.add(new LineRef(chunk, line));
         }
      }

      Map<String, String> knownZp = new HashMap<>();
      String currentValA = null;
      String currentValX = null;
      String currentValY = null;
      LineRef loadRefA = null;
      LineRef loadRefX = null;
      LineRef loadRefY = null;
      String currentScope = "";
      Map<String, String> currentScopeAliases = null;

      for(int i = 0; i < allLines.size(); i++) {
         LineRef ref = allLines.get(i);
         AsmLine line = ref.line;

         if(line instanceof AsmScopeBegin) {
            currentScope = ((AsmScopeBegin) line).getLabel();
            currentScopeAliases = scopeAliasMap.get(currentScope);
            knownZp.clear();
            currentValA = null; currentValX = null; currentValY = null;
            loadRefA = null; loadRefX = null; loadRefY = null;
            continue;
         }
         if(line instanceof AsmScopeEnd) {
            currentScope = "";
            currentScopeAliases = null;
            knownZp.clear();
            currentValA = null; currentValX = null; currentValY = null;
            loadRefA = null; loadRefX = null; loadRefY = null;
            continue;
         }
         if(line instanceof AsmInlineKickAsm) {
            knownZp.clear();
            currentValA = null; currentValX = null; currentValY = null;
            loadRefA = null; loadRefX = null; loadRefY = null;
            continue;
         }
         if(line instanceof AsmLabel) {
            String lbl = ((AsmLabel) line).getLabel();
            if(lbl != null && jumpTargets.contains(lbl.trim())) {
               // Real jump target entered from elsewhere: invalidate state
               knownZp.clear();
               currentValA = null; currentValX = null; currentValY = null;
               loadRefA = null; loadRefX = null; loadRefY = null;
            }
            continue;
         }

         if(line instanceof AsmInstruction) {
            AsmInstruction inst = (AsmInstruction) line;
            if(!ref.chunk.getLines().contains(inst)) {
               continue; // already removed
            }
            if(inst.isNoOptimize() || inst.getCpuOpcode() == null) {
               knownZp.clear();
               currentValA = null; currentValX = null; currentValY = null;
               loadRefA = null; loadRefX = null; loadRefY = null;
               continue;
            }

            CpuOpcode opcode = inst.getCpuOpcode();
            String mnem = opcode.getMnemonic();
            CpuAddressingMode mode = opcode.getAddressingMode();
            String operand = inst.getOperand1();

            // Subroutine call (JSR) - handled specifically before general jumps!
            if("jsr".equals(mnem)) {
               currentValA = null; currentValX = null; currentValY = null;
               loadRefA = null; loadRefX = null; loadRefY = null;
               String callee = (operand != null) ? operand.trim() : null;
               if(callee != null && procMap.containsKey(callee) && !Boolean.TRUE.equals(totalUnknownWrites.get(callee))) {
                  Set<String> writes = totalZpWrites.get(callee);
                  if(writes != null) {
                     for(String w : writes) {
                        knownZp.remove(w);
                        final String zpKey = "zp:" + w;
                        knownZp.values().removeIf(val -> val.equals(zpKey));
                     }
                  }
               } else {
                  knownZp.clear();
               }
               continue;
            }

            // Control flow breaks (JMP, RTS, RTI, and conditional branches)
            if("jmp".equals(mnem) || "rts".equals(mnem) || "rti".equals(mnem) || opcode.isJump()) {
               knownZp.clear();
               currentValA = null; currentValX = null; currentValY = null;
               loadRefA = null; loadRefX = null; loadRefY = null;
               continue;
            }

            // Loads of immediate values into registers
            if(mode == CpuAddressingMode.IMM && operand != null && operand.startsWith("#")) {
               if("lda".equals(mnem)) {
                  currentValA = operand;
                  loadRefA = ref;
                  continue;
               } else if("ldx".equals(mnem)) {
                  currentValX = operand;
                  loadRefX = ref;
                  continue;
               } else if("ldy".equals(mnem)) {
                  currentValY = operand;
                  loadRefY = ref;
                  continue;
               }
            }

            // Loads of zero-page variables into registers
            if(mode == CpuAddressingMode.ZP && operand != null && !Pass5RedundantStoreAndReloadElimination.isHardwareAddress(operand)) {
               String canon = Pass5RedundantStoreAndReloadElimination.getCanonical(operand, currentScopeAliases, globalAliasMap);
               String varKey = (canon != null && !canon.isEmpty()) ? canon : operand.trim();
               if("lda".equals(mnem)) {
                  currentValA = "zp:" + varKey;
                  loadRefA = ref;
                  continue;
               } else if("ldx".equals(mnem)) {
                  currentValX = "zp:" + varKey;
                  loadRefX = ref;
                  continue;
               } else if("ldy".equals(mnem)) {
                  currentValY = "zp:" + varKey;
                  loadRefY = ref;
                  continue;
               }
            }

            // Register-to-register transfers
            if("tax".equals(mnem)) {
               currentValX = currentValA;
               loadRefX = null;
               continue;
            } else if("txa".equals(mnem)) {
               currentValA = currentValX;
               loadRefA = null;
               continue;
            } else if("tay".equals(mnem)) {
               currentValY = currentValA;
               loadRefY = null;
               continue;
            } else if("tya".equals(mnem)) {
               currentValA = currentValY;
               loadRefA = null;
               continue;
            }

            // Register overwrites from memory or other sources
            if("lda".equals(mnem) || "pla".equals(mnem) || "lax".equals(mnem) ||
               "adc".equals(mnem) || "sbc".equals(mnem) || "and".equals(mnem) || "ora".equals(mnem) || "eor".equals(mnem) ||
               "alr".equals(mnem) || "arr".equals(mnem) || "anc".equals(mnem)) {
               currentValA = null;
               loadRefA = null;
            }
            if("asl".equals(mnem) || "lsr".equals(mnem) || "rol".equals(mnem) || "ror".equals(mnem)) {
               if(mode == CpuAddressingMode.NON || operand == null || "a".equalsIgnoreCase(operand.trim())) {
                  currentValA = null;
                  loadRefA = null;
               }
            }
            if("ldx".equals(mnem) || "plx".equals(mnem) || "tsx".equals(mnem) || "lax".equals(mnem) ||
               "inx".equals(mnem) || "dex".equals(mnem) || "axs".equals(mnem)) {
               currentValX = null;
               loadRefX = null;
            }
            if("ldy".equals(mnem) || "ply".equals(mnem) || "iny".equals(mnem) || "dey".equals(mnem)) {
               currentValY = null;
               loadRefY = null;
            }

            // Stores into Zero-Page
            if(mode == CpuAddressingMode.ZP && operand != null && !Pass5RedundantStoreAndReloadElimination.isHardwareAddress(operand)) {
               String canon = Pass5RedundantStoreAndReloadElimination.getCanonical(operand, currentScopeAliases, globalAliasMap);
               String target = (canon != null && !canon.isEmpty()) ? canon : operand.trim();
               String storedVal = null;
               LineRef loadRef = null;
               String reg = null;

               if("sta".equals(mnem)) { storedVal = currentValA; loadRef = loadRefA; reg = "A"; }
               else if("stx".equals(mnem)) { storedVal = currentValX; loadRef = loadRefX; reg = "X"; }
               else if("sty".equals(mnem)) { storedVal = currentValY; loadRef = loadRefY; reg = "Y"; }

               if(storedVal != null) {
                  if(storedVal.equals(knownZp.get(target)) || storedVal.equals("zp:" + target)) {
                     // REDUNDANT STORE IDENTIFIED!
                     getLog().append("Pass 5: Eliminating redundant store across JSR: " + inst.getAsm());
                     ref.chunk.getLines().remove(ref.line);
                     modified = true;

                     if(loadRef != null && canEliminatePrecedingLoad(allLines, i, loadRef, reg)) {
                        getLog().append("Pass 5: Eliminating dead preceding load: " + ((AsmInstruction) loadRef.line).getAsm());
                        loadRef.chunk.getLines().remove(loadRef.line);
                        if("A".equals(reg)) loadRefA = null;
                        else if("X".equals(reg)) loadRefX = null;
                        else if("Y".equals(reg)) loadRefY = null;
                     }
                     continue;
                  } else {
                     // Invalidate any aliases depending on target
                     final String zpTarget = "zp:" + target;
                     knownZp.values().removeIf(val -> val.equals(zpTarget));
                     knownZp.put(target, storedVal);
                  }
               } else {
                  knownZp.remove(target);
                  final String zpTarget = "zp:" + target;
                  knownZp.values().removeIf(val -> val.equals(zpTarget));
               }
            } else if(ZP_MODIFY_MNEMONICS.contains(mnem) && operand != null) {
               // Any other instruction that modifies ZP memory invalidates that ZP location
               String canon = Pass5RedundantStoreAndReloadElimination.getCanonical(operand, currentScopeAliases, globalAliasMap);
               String modifiedZp = (canon != null && !canon.isEmpty()) ? canon : operand.trim();
               knownZp.remove(modifiedZp);
               final String zpMod = "zp:" + modifiedZp;
               knownZp.values().removeIf(val -> val.equals(zpMod));
               if(zpMod.equals(currentValA)) { currentValA = null; loadRefA = null; }
               if(zpMod.equals(currentValX)) { currentValX = null; loadRefX = null; }
               if(zpMod.equals(currentValY)) { currentValY = null; loadRefY = null; }
            }
         }
      }

      return modified;
   }
}
