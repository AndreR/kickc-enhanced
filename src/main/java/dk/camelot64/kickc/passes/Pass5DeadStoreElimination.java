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

         // Group lines by scope
         Map<String, List<LineRef>> scopeLinesMap = new LinkedHashMap<>();
         String currentScope = "";
         for(LineRef ref : allLines) {
            if(ref.line instanceof AsmScopeBegin) {
               currentScope = ((AsmScopeBegin) ref.line).getLabel();
            } else if(ref.line instanceof AsmScopeEnd) {
               currentScope = "";
            } else if(currentScope != null && !currentScope.isEmpty()) {
               scopeLinesMap.computeIfAbsent(currentScope, k -> new ArrayList<>()).add(ref);
            }
         }

         for(Map.Entry<String, List<LineRef>> entry : scopeLinesMap.entrySet()) {
            List<LineRef> sLines = entry.getValue();

            // 1. Identify all candidate procedure-local SSA temporaries in this scope
            Set<String> ssaCandidates = new HashSet<>();
            for(LineRef ref : sLines) {
               if(ref.line instanceof AsmLabelDecl) {
                  AsmLabelDecl decl = (AsmLabelDecl) ref.line;
                  String name = decl.getName();
                  if(isSsaTemp(name)) {
                     ssaCandidates.add(getBaseVar(name));
                     int dotIdx = name.lastIndexOf('.');
                     if(dotIdx != -1) {
                        ssaCandidates.add(name.substring(dotIdx + 1));
                     }
                  }
               }
            }

            if(ssaCandidates.isEmpty()) {
               continue;
            }

            // 2. Scan scope for any READ / ALIAS / ADDRESS-OF references to candidates
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
                     if(asm.contains(cand)) {
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
                     if(op.contains(cand)) {
                        boolean isDirectStore = (mnem.equals("sta") || mnem.equals("stx") || mnem.equals("sty")) &&
                                                (mode.equals(CpuAddressingMode.ZP) || mode.equals(CpuAddressingMode.ABS));
                        // If it's not a direct store (or if mode is indirect like (cand),y or address-of #<cand), it's a read!
                        if(!isDirectStore || op.contains("(") || op.contains("#")) {
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
