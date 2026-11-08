package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.SegmentTypeInfo;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.values.ScopeRef;

import java.util.*;

/**
 * Feature 04 (Issue #5): Zero-Syntax Pointer-to-SMC Uplift (Self-Modifying Code in RAM)
 *
 * For procedures residing in writable RAM (RAM_RESIDENT or general RAM, never ROM):
 * Detects sequential pointer dereferences (e.g. *ptr, *ptr++) currently using indirect-indexed
 * addressing (LDA/STA/ADC/ORA/EOR/AND/CMP (ptr),Y) with Y=0, and transforms them into
 * direct self-modifying code (SMC) absolute addressing (LDA/STA $xxxx).
 *
 * Benefits:
 * - Saves 2 cycles per byte on reads/writes (LDA $xxxx is 4 cycles vs 5-6 cycles for ($zp),Y)
 * - Eliminates LDY #0 setup before dereferencing (saving 2 cycles & register pressure)
 * - Frees 2 Zero-Page bytes per pointer loop as the pointer variable in ZP is eliminated
 */
public class Pass5PointerToSmcOptimization extends Pass5AsmOptimization {

   private static int smcCounter = 0;
   private boolean performed = false;

   public Pass5PointerToSmcOptimization(Program program) {
      super(program);
   }

   public static class LineRef {
      final AsmChunk chunk;
      final int indexInChunk;
      final AsmLine line;
      final String scope;

      LineRef(AsmChunk chunk, int indexInChunk, AsmLine line, String scope) {
         this.chunk = chunk;
         this.indexInChunk = indexInChunk;
         this.line = line;
         this.scope = scope;
      }
   }

   private static String stripZp(String op) {
      if(op == null) return null;
      String t = op.trim();
      if(t.startsWith(".z ")) return t.substring(3).trim();
      return t;
   }

   private static String extractPtrFromIndY(String operand) {
      if(operand == null) return null;
      String t = stripZp(operand);
      if(t.startsWith("(") && t.contains(")")) {
         int closeIdx = t.indexOf(')');
         return stripZp(t.substring(1, closeIdx));
      }
      return t;
   }

   private static boolean matchesVarLo(String operand, String varName, String varAddr, Map<String, String> aliases) {
      if(operand == null) return false;
      String t = stripZp(operand);
      if(varName != null && t.equals(varName)) return true;
      if(varAddr != null && t.equals(varAddr)) return true;
      if(aliases != null) {
         String res = aliases.get(t);
         if(res != null) {
            String resClean = stripZp(res);
            if(varName != null && resClean.equals(varName)) return true;
            if(varAddr != null && resClean.equals(varAddr)) return true;
         }
      }
      return false;
   }

   private static boolean matchesVarHi(String operand, String varName, String varAddr, Map<String, String> aliases) {
      if(operand == null) return false;
      String t = stripZp(operand);
      if(t.endsWith("+1")) {
         String base = t.substring(0, t.length() - 2).trim();
         return matchesVarLo(base, varName, varAddr, aliases);
      }
      try {
         if(varAddr != null) {
            int addr = Integer.parseInt(varAddr);
            int opAddr = Integer.parseInt(t);
            if(opAddr == addr + 1) return true;
         }
      } catch(NumberFormatException ignored) {}
      return false;
   }

   @Override
   public boolean optimize() {
      if(performed) return false;
      performed = true;

      boolean overallModified = false;
      boolean passModified = true;

      while(passModified) {
         passModified = false;

         List<LineRef> allLines = new ArrayList<>();
         String currentScope = null;
         for(AsmChunk chunk : getAsmProgram().getChunks()) {
            List<AsmLine> lines = chunk.getLines();
            for(int i = 0; i < lines.size(); i++) {
               AsmLine line = lines.get(i);
               if(line instanceof AsmScopeBegin) {
                  currentScope = ((AsmScopeBegin) line).getLabel();
               } else if(line instanceof AsmScopeEnd) {
                  currentScope = null;
               }
               allLines.add(new LineRef(chunk, i, line, currentScope));
            }
         }

         // Group lines by scope and collect aliases
         Map<String, Map<String, String>> scopeAliases = new HashMap<>();
         Map<String, List<LineRef>> scopeLines = new HashMap<>();
         for(LineRef ref : allLines) {
            if(ref.scope != null) {
               scopeLines.computeIfAbsent(ref.scope, k -> new ArrayList<>()).add(ref);
               if(ref.line instanceof AsmLabelDecl decl) {
                  scopeAliases.computeIfAbsent(ref.scope, k -> new HashMap<>()).put(decl.getName(), decl.getValue());
               }
            }
         }

         Scope rootScope = getProgram().getScope();

         // Process each procedure scope
         for(Map.Entry<String, List<LineRef>> entry : scopeLines.entrySet()) {
            String scopeName = entry.getKey();
            List<LineRef> linesInScope = entry.getValue();

            // Verify procedure is in writable RAM (never ROM)
            Procedure proc = null;
            for(Procedure p : rootScope.getAllProcedures(true)) {
               if(p.getLocalName().equals(scopeName) || p.getFullName().equals(scopeName)) {
                  proc = p;
                  break;
               }
            }

            if(proc != null) {
               if(proc.getInterruptType() != null) continue;
               String seg = proc.getSegmentCode();
               SegmentTypeInfo segType = getProgram().getSegmentType(seg);
               if(segType != null && segType.isRom()) {
                  continue; // Safety: Never generate SMC in ROM!
               }
            }

            Map<String, String> aliases = scopeAliases.get(scopeName);

            // Find candidates for Pointer-to-SMC:
            // Look for instructions with (ptr),y addressing mode
            for(int i = 0; i < linesInScope.size(); i++) {
               LineRef derefRef = linesInScope.get(i);
               if(!(derefRef.line instanceof AsmInstruction)) continue;
               AsmInstruction derefInst = (AsmInstruction) derefRef.line;
               if(derefInst.isNoOptimize() || derefInst.getCpuOpcode() == null) continue;

               
               if(!CpuAddressingMode.IZY.equals(derefInst.getCpuOpcode().getAddressingMode())) {
                  continue;
               }

               String ptrVar = extractPtrFromIndY(derefInst.getOperand1());
               if(ptrVar == null) continue;
               String ptrAddr = (aliases != null) ? aliases.get(ptrVar) : null;

               // Analyze all references to this pointer in the scope:
               // All references must be strictly:
               // 1. Initializations: STA ptr / STA ptr+1
               // 2. Dereferences: (ptr),y
               // 3. Increments: INC ptr / INC ptr+1
               boolean safeCandidate = true;
               List<LineRef> initLoLines = new ArrayList<>();
               List<LineRef> initHiLines = new ArrayList<>();
               List<LineRef> incLoLines = new ArrayList<>();
               List<LineRef> incHiLines = new ArrayList<>();
               List<LineRef> derefLines = new ArrayList<>();

               for(LineRef ref : linesInScope) {
                  if(!(ref.line instanceof AsmInstruction)) continue;
                  AsmInstruction inst = (AsmInstruction) ref.line;
                  if(inst.getCpuOpcode() == null) continue;
                  String mnem = inst.getCpuOpcode().getMnemonic().toLowerCase();
                  String op = inst.getOperand1();

                  if(ref == derefRef || (derefLines.contains(ref)) || (CpuAddressingMode.IZY.equals(inst.getCpuOpcode().getAddressingMode()) && ptrVar.equals(stripZp(op)))) {
                     derefLines.add(ref);
                  } else if("sta".equals(mnem) && matchesVarLo(op, ptrVar, ptrAddr, aliases)) {
                     initLoLines.add(ref);
                  } else if("sta".equals(mnem) && matchesVarHi(op, ptrVar, ptrAddr, aliases)) {
                     initHiLines.add(ref);
                  } else if("inc".equals(mnem) && matchesVarLo(op, ptrVar, ptrAddr, aliases)) {
                     incLoLines.add(ref);
                  } else if("inc".equals(mnem) && matchesVarHi(op, ptrVar, ptrAddr, aliases)) {
                     incHiLines.add(ref);
                  } else {
                     // Check if instruction references this pointer variable
                     if(matchesVarLo(op, ptrVar, ptrAddr, aliases) || matchesVarHi(op, ptrVar, ptrAddr, aliases)) {
                        safeCandidate = false;
                        break;
                     }
                  }
               }

               if(!safeCandidate || derefLines.isEmpty() || initLoLines.isEmpty() || initHiLines.isEmpty()) {
                  continue;
               }

               // Valid SMC candidate found!
               String smcLabel = "smc_ptr_" + (++smcCounter);
               CpuOpcode absOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode(derefInst.getCpuOpcode().getMnemonic(), CpuAddressingMode.ABS, false);
               CpuOpcode staAbs = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("sta", CpuAddressingMode.ABS, false);
               CpuOpcode incAbs = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("inc", CpuAddressingMode.ABS, false);

               if(absOp == null || staAbs == null || incAbs == null) continue;

               // Step 1: Add label before dereference instruction and convert to ABS
               int derefIdxInChunk = derefRef.chunk.getLines().indexOf(derefInst);
               if(derefIdxInChunk < 0) continue;

               AsmLabel smcLabelInst = new AsmLabel(smcLabel);
               smcLabelInst.setNoOptimize(true);
               derefRef.chunk.getLines().add(derefIdxInChunk, smcLabelInst);
               derefInst.setCpuOpcode(absOp);
               derefInst.setOperand1("$ffff");
               derefInst.setNoOptimize(true);

               // Remove redundant LDY #0 directly preceding dereference if present
               if(derefIdxInChunk > 0) {
                  AsmLine prevLine = derefRef.chunk.getLines().get(derefIdxInChunk - 1);
                  if(prevLine instanceof AsmInstruction prevInst && prevInst.getCpuOpcode() != null) {
                     if("ldy".equalsIgnoreCase(prevInst.getCpuOpcode().getMnemonic()) &&
                        CpuAddressingMode.IMM.equals(prevInst.getCpuOpcode().getAddressingMode()) &&
                        ("#0".equals(prevInst.getOperand1()) || "0".equals(prevInst.getOperand1()))) {
                        derefRef.chunk.getLines().remove(prevInst);
                     }
                  }
               }

               // Step 2: Redirect STA ptr / STA ptr+1 to modify instruction operand
               for(LineRef r : initLoLines) {
                  AsmInstruction inst = (AsmInstruction) r.line;
                  inst.setCpuOpcode(staAbs);
                  inst.setOperand1(smcLabel + "+1");
                  inst.setNoOptimize(true);
               }
               for(LineRef r : initHiLines) {
                  AsmInstruction inst = (AsmInstruction) r.line;
                  inst.setCpuOpcode(staAbs);
                  inst.setOperand1(smcLabel + "+2");
                  inst.setNoOptimize(true);
               }

               // Step 3: Redirect INC ptr / INC ptr+1 to increment instruction operand
               for(LineRef r : incLoLines) {
                  AsmInstruction inst = (AsmInstruction) r.line;
                  inst.setCpuOpcode(incAbs);
                  inst.setOperand1(smcLabel + "+1");
                  inst.setNoOptimize(true);
               }
               for(LineRef r : incHiLines) {
                  AsmInstruction inst = (AsmInstruction) r.line;
                  inst.setCpuOpcode(incAbs);
                  inst.setOperand1(smcLabel + "+2");
                  inst.setNoOptimize(true);
               }

               passModified = true;
               overallModified = true;
               break;
            }

            if(passModified) break;
         }
      }

      return overallModified;
   }
}
