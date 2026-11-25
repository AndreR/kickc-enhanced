package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.Registers;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Symbol;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypePointer;
import dk.camelot64.kickc.model.types.SymbolTypeStruct;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.model.values.SymbolRef;

import java.util.*;

/**
 * Tail Call Optimization (TCO):
 * Replaces 'JSR <target>' followed immediately by 'RTS' with 'JMP <target>' (and removes the now-unreachable RTS).
 *
 * Requirements for valid tail call transformation:
 * 1. Caller and Callee must reside in the exact same memory segment and the exact same bank.
 * 2. Caller and Callee must have identical return calling conventions:
 *    - Same return type (void/void, bool/bool, or same size non-struct types).
 *    - Same return mechanism (both carry-flag returns, or both AX register returns, or identical return register/allocation).
 * 3. Neither procedure is an interrupt handler (interrupt handlers return via RTI).
 * 4. General calling conventions match and are not STACK_CALL.
 */
public class Pass5TailCallOptimization extends Pass5AsmOptimization {

   public Pass5TailCallOptimization(Program program) {
      super(program);
   }

   private Procedure findProcedure(String name, Procedure caller, Map<String, Procedure> procMap) {
      if(name == null || name.isEmpty()) return null;
      String cleanName = name.trim();
      if(cleanName.startsWith("::")) {
         cleanName = cleanName.substring(2);
      }
      Procedure p = procMap.get(cleanName);
      if(p != null) return p;

      if(caller != null) {
         Symbol sym = caller.findSymbol(cleanName);
         if(sym instanceof Procedure) {
            return (Procedure) sym;
         }
      }

      Symbol sym = getProgram().getScope().getGlobalSymbol(cleanName);
      if(sym instanceof Procedure) {
         return (Procedure) sym;
      }
      sym = getProgram().getScope().findSymbol(cleanName);
      if(sym instanceof Procedure) {
         return (Procedure) sym;
      }
      return null;
   }

   private boolean areReturnTypesCompatible(SymbolType callerType, SymbolType calleeType) {
      if(callerType == null || calleeType == null) {
         return false;
      }
      if(SymbolType.VOID.equals(callerType) || SymbolType.VOID.equals(calleeType)) {
         return SymbolType.VOID.equals(callerType) && SymbolType.VOID.equals(calleeType);
      }
      if(callerType.equals(calleeType)) {
         return true;
      }
      boolean callerIsBool = SymbolType.BOOLEAN.equals(callerType);
      boolean calleeIsBool = SymbolType.BOOLEAN.equals(calleeType);
      if(callerIsBool || calleeIsBool) {
         return callerIsBool && calleeIsBool;
      }
      if(callerType instanceof SymbolTypeStruct || calleeType instanceof SymbolTypeStruct) {
         return false;
      }
      if(callerType instanceof SymbolTypePointer && calleeType instanceof SymbolTypePointer) {
         return true;
      }
      return callerType.getSizeBytes() == calleeType.getSizeBytes();
   }

   private boolean areReturnConventionsCompatible(Procedure caller, Procedure callee, Map<String, List<AsmLine>> scopeLinesMap) {
      if(caller.getReturnType() == null || callee.getReturnType() == null) {
         return false;
      }
      if(!SymbolType.VOID.equals(caller.getReturnType()) && SymbolType.VOID.equals(callee.getReturnType())) {
         return isNonVoidCallerToVoidCalleeSafe(caller, callee, scopeLinesMap);
      }
      if(!areReturnTypesCompatible(caller.getReturnType(), callee.getReturnType())) {
         return false;
      }
      if(SymbolType.VOID.equals(caller.getReturnType())) {
         return true;
      }
      // Carry flag return
      if(caller.isCarryReturn() != callee.isCarryReturn()) {
         return false;
      }
      if(caller.isCarryReturn()) {
         return true;
      }
      // AX register pair calling convention
      if(caller.isAxConvention() != callee.isAxConvention()) {
         return false;
      }
      if(caller.isAxConvention()) {
         return true;
      }
      // Auto-A-register return ABI (GEN-06)
      if(caller.isAReturn() != callee.isAReturn()) {
         return false;
      }
      if(caller.isAReturn()) {
         return true;
      }
      // Allocation check for return variable
      Variable callerRet = caller.getLocalVar("return");
      Variable calleeRet = callee.getLocalVar("return");
      if(callerRet != null && calleeRet != null) {
         Registers.Register callerAlloc = callerRet.getAllocation();
         Registers.Register calleeAlloc = calleeRet.getAllocation();
         if(callerAlloc != null && calleeAlloc != null) {
            if(!callerAlloc.equals(calleeAlloc)) {
               return false;
            }
         } else if(callerAlloc != calleeAlloc) {
            return false;
         }
      } else if(callerRet != calleeRet) {
         return false;
      }
      return true;
   }

   private boolean areSegmentsCompatible(Procedure caller, Procedure callee) {
      if(!Objects.equals(caller.getSegmentCode(), callee.getSegmentCode())) {
         return false;
      }
      if(!Objects.equals(caller.getBank(), callee.getBank())) {
         return false;
      }
      return true;
   }

   private boolean isNonVoidCallerToVoidCalleeSafe(Procedure caller, Procedure callee,
                                                     Map<String, List<AsmLine>> scopeLinesMap) {
      if(caller.getReturnType() instanceof SymbolTypeStruct) {
         return false;
      }
      List<AsmLine> calleeLines = findScopeLines(callee, scopeLinesMap);
      if(calleeLines == null || calleeLines.isEmpty()) {
         return false;
      }

      boolean checkA = caller.isAReturn() || caller.isAxConvention();
      boolean checkX = caller.isAxConvention();
      boolean checkY = false;
      boolean checkC = caller.isCarryReturn();

      Variable callerRet = caller.getLocalVar("return");
      if(callerRet != null && callerRet.getAllocation() != null) {
         Registers.RegisterType rType = callerRet.getAllocation().getType();
         if(Registers.RegisterType.REG_A.equals(rType)) checkA = true;
         else if(Registers.RegisterType.REG_X.equals(rType)) checkX = true;
         else if(Registers.RegisterType.REG_Y.equals(rType)) checkY = true;
      }

      Set<String> protectedAddrs = new HashSet<>();
      List<AsmLine> callerLines = findScopeLines(caller, scopeLinesMap);
      if(callerLines != null) {
         String retVal = findLabelValue(callerLines, "return");
         if(retVal != null) {
            protectedAddrs.add(retVal);
            Integer baseAddr = parseSimpleAddr(retVal);
            if(baseAddr != null) {
               int size = Math.max(1, caller.getReturnType().getSizeBytes());
               for(int b = 0; b < size; b++) {
                  protectedAddrs.add("$" + Integer.toHexString(baseAddr + b));
                  protectedAddrs.add(Integer.toString(baseAddr + b));
               }
            }
         }
      }

      Map<String, String> calleeLabels = new HashMap<>();
      for(AsmLine line : calleeLines) {
         if(line instanceof AsmLabelDecl decl) {
            calleeLabels.put(decl.getName().trim(), decl.getValue().trim());
         }
      }

      for(AsmLine line : calleeLines) {
         if(line instanceof AsmInlineKickAsm) {
            return false;
         }
         if(!(line instanceof AsmInstruction inst) || inst.getCpuOpcode() == null) {
            continue;
         }
         String mnem = inst.getCpuOpcode().getMnemonic();
         CpuAddressingMode mode = inst.getCpuOpcode().getAddressingMode();
         if(mnem.equals("jsr")) {
            return false;
         }
         var clobber = inst.getCpuOpcode().getClobber();
         if(checkA && clobber.isRegisterA()) return false;
         if(checkX && clobber.isRegisterX()) return false;
         if(checkY && clobber.isRegisterY()) return false;
         if(checkC && clobber.isFlagC()) return false;

         boolean isMemWrite = mnem.equals("sta") || mnem.equals("stx") || mnem.equals("sty") || mnem.equals("sax")
               || mnem.equals("inc") || mnem.equals("dec") || mnem.equals("dcp") || mnem.equals("isc")
               || mnem.equals("slo") || mnem.equals("sre") || mnem.equals("rla") || mnem.equals("rra")
               || ((mnem.equals("asl") || mnem.equals("lsr") || mnem.equals("rol") || mnem.equals("ror"))
                   && !mode.equals(CpuAddressingMode.NON));
         if(isMemWrite && !protectedAddrs.isEmpty()) {
            if(mode.equals(CpuAddressingMode.IZX) || mode.equals(CpuAddressingMode.IZY) || mode.equals(CpuAddressingMode.IND)) {
               return false;
            }
            String op = inst.getOperand1();
            if(op != null) {
               String clean = op.trim();
               if(clean.startsWith(".z ")) clean = clean.substring(3).trim();
               int comma = clean.indexOf(',');
               if(comma != -1) clean = clean.substring(0, comma).trim();
               String resolved = calleeLabels.getOrDefault(clean, clean);
               if(protectedAddrs.contains(clean) || protectedAddrs.contains(resolved)) {
                  return false;
               }
            }
         }
      }
      return true;
   }

   private static List<AsmLine> findScopeLines(Procedure proc, Map<String, List<AsmLine>> scopeLinesMap) {
      if(proc == null) return null;
      List<AsmLine> lines = scopeLinesMap.get(proc.getFullName());
      if(lines != null) return lines;
      lines = scopeLinesMap.get(proc.getLocalName());
      if(lines != null) return lines;
      return scopeLinesMap.get(proc.getLabel().getLocalName());
   }

   private static String findLabelValue(List<AsmLine> lines, String labelName) {
      for(AsmLine line : lines) {
         if(line instanceof AsmLabelDecl decl && decl.getName().trim().equals(labelName)) {
            return decl.getValue().trim();
         }
      }
      return null;
   }

   private static Integer parseSimpleAddr(String val) {
      if(val == null || val.isEmpty()) return null;
      try {
         if(val.startsWith("$")) return Integer.parseInt(val.substring(1), 16);
         if(Character.isDigit(val.charAt(0))) return Integer.parseInt(val);
      } catch(NumberFormatException ignored) {
      }
      return null;
   }

   private boolean isTailCallCompatible(Procedure caller, Procedure callee, Map<String, List<AsmLine>> scopeLinesMap) {
      if(caller == null || callee == null) {
         return false;
      }
      if(caller.getInterruptType() != null || callee.getInterruptType() != null) {
         return false;
      }
      if(caller.getCallingConvention() != callee.getCallingConvention()) {
         return false;
      }
      if(Procedure.CallingConvention.STACK_CALL.equals(caller.getCallingConvention())) {
         return false;
      }
      if(!areSegmentsCompatible(caller, callee)) {
         return false;
      }
      if(!areReturnConventionsCompatible(caller, callee, scopeLinesMap)) {
         return false;
      }
      return true;
   }

   public boolean optimize() {
      CpuOpcode jmpOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("jmp", CpuAddressingMode.ABS, false);
      if(jmpOpcode == null) return false;

      // Precompute procedure lookup map
      Map<String, Procedure> procMap = new HashMap<>();
      for(Procedure proc : getProgram().getScope().getAllProcedures(true)) {
         procMap.put(proc.getFullName(), proc);
         procMap.put(proc.getLocalName(), proc);
         procMap.put(proc.getLabel().getLocalName(), proc);
      }

      boolean overallModified = false;
      boolean passModified = true;

      while(passModified) {
         passModified = false;

         // Build sequential line list across all chunks tracking active scope
         List<LineRef> allLines = new ArrayList<>();
         Map<String, List<AsmLine>> scopeLinesMap = new HashMap<>();
         String currentScope = null;
         for(AsmChunk chunk : getAsmProgram().getChunks()) {
            String chunkScope = chunk.getScopeLabel();
            if(chunkScope != null) currentScope = chunkScope;
            List<AsmLine> lines = chunk.getLines();
            for(int i = 0; i < lines.size(); i++) {
               AsmLine line = lines.get(i);
               if(line instanceof AsmScopeBegin) {
                  currentScope = ((AsmScopeBegin) line).getLabel();
               }
               allLines.add(new LineRef(chunk, i, line, currentScope));
               if(currentScope != null && !(line instanceof AsmScopeBegin) && !(line instanceof AsmScopeEnd)) {
                  scopeLinesMap.computeIfAbsent(currentScope, k -> new ArrayList<>()).add(line);
               }
               if(line instanceof AsmScopeEnd) {
                  currentScope = null;
               }
            }
         }

         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref1 = allLines.get(i);
            if(!(ref1.line instanceof AsmInstruction)) continue;

            AsmInstruction inst1 = (AsmInstruction) ref1.line;
            if(inst1.isNoOptimize()) continue;
            if(inst1.getCpuOpcode() == null) continue;
            if(!inst1.getCpuOpcode().getMnemonic().equals("jsr")) continue;

            // Exclude root scope and calls to main
            if(ref1.scope != null && ref1.scope.equals(ScopeRef.ROOT.getFullName())) continue;
            if(inst1.getOperand1() != null && inst1.getOperand1().equals(SymbolRef.MAIN_PROC_NAME)) continue;

            // Verify tail call compatibility (same segment/bank, identical return calling conventions)
            Procedure caller = findProcedure(ref1.scope, null, procMap);
            Procedure callee = findProcedure(inst1.getOperand1(), caller, procMap);
            if(!isTailCallCompatible(caller, callee, scopeLinesMap)) {
               continue;
            }

            // Find next executable instruction
            LineRef ref2 = null;
            boolean blocked = false;
            boolean hasInterveningLabel = false;
            for(int j = i + 1; j < allLines.size(); j++) {
               LineRef candidate = allLines.get(j);
               if(candidate.line instanceof AsmComment || candidate.line instanceof AsmConstant || candidate.line instanceof AsmLabelDecl) {
                  continue;
               }
               if(candidate.line instanceof AsmLabel) {
                  // A label target exists between JSR and RTS: another branch might jump to RTS.
                  // We can still convert JSR -> JMP, but must keep the RTS for the label!
                  hasInterveningLabel = true;
                  continue;
               }
               if(candidate.line instanceof AsmScopeBegin || candidate.line instanceof AsmScopeEnd) {
                  blocked = true;
                  break;
               }
               if(candidate.line instanceof AsmInstruction) {
                  ref2 = candidate;
                  break;
               }
               // Any other construct (e.g. data, inline asm) blocks tail call
               blocked = true;
               break;
            }

            if(blocked || ref2 == null) continue;

            AsmInstruction inst2 = (AsmInstruction) ref2.line;
            if(inst2.isNoOptimize() || inst2.getCpuOpcode() == null) continue;

            if(inst2.getCpuOpcode().getMnemonic().equals("rts")) {
               getLog().append("Tail Call Optimization: Replacing " + inst1.getAsm() + "; rts with jmp " + inst1.getOperand1());
               inst1.setCpuOpcode(jmpOpcode);
               if(!hasInterveningLabel) {
                  ref2.chunk.getLines().remove(inst2);
               }
               passModified = true;
               overallModified = true;
               break; // Restart scan after list modification
            }
         }
      }

      return overallModified;
   }
}
