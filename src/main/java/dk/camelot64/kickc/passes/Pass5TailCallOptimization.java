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

   private static class LineRef {
      final AsmChunk chunk;
      final int indexInChunk;
      final AsmLine line;
      final String scopeLabel;

      LineRef(AsmChunk chunk, int indexInChunk, AsmLine line, String scopeLabel) {
         this.chunk = chunk;
         this.indexInChunk = indexInChunk;
         this.line = line;
         this.scopeLabel = scopeLabel;
      }
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

   private boolean areReturnConventionsCompatible(Procedure caller, Procedure callee) {
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

   private boolean isTailCallCompatible(Procedure caller, Procedure callee) {
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
      if(!areReturnConventionsCompatible(caller, callee)) {
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
            if(ref1.scopeLabel != null && ref1.scopeLabel.equals(ScopeRef.ROOT.getFullName())) continue;
            if(inst1.getOperand1() != null && inst1.getOperand1().equals(SymbolRef.MAIN_PROC_NAME)) continue;

            // Verify tail call compatibility (same segment/bank, identical return calling conventions)
            Procedure caller = findProcedure(ref1.scopeLabel, null, procMap);
            Procedure callee = findProcedure(inst1.getOperand1(), caller, procMap);
            if(!isTailCallCompatible(caller, callee)) {
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
