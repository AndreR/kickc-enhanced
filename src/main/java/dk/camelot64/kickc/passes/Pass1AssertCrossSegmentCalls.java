package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.CallGraph;
import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.SegmentTypeInfo;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.values.ProcedureRef;

import java.util.Collection;

/**
 * Asserts that there are no illegal direct cross-bank or cross-overlay procedure calls.
 */
public class Pass1AssertCrossSegmentCalls extends Pass1Base {

   public Pass1AssertCrossSegmentCalls(Program program) {
      super(program);
   }

   @Override
   public boolean step() {
      CallGraph callGraph = getProgram().getCallGraph();
      Collection<Procedure> procedures = getProgramScope().getAllProcedures(true);
      for(Procedure caller : procedures) {
         String callerSeg = caller.getSegmentCode();
         SegmentTypeInfo callerType = getProgram().getSegmentType(callerSeg);
         if(callerType == null) continue;

         CallGraph.CallBlock callBlock = callGraph.getCallBlock(caller.getRef());
         if(callBlock == null) continue;

         for(CallGraph.CallBlock.Call call : callBlock.getCalls()) {
            ProcedureRef targetProcRef = call.getProcedure();
            if(targetProcRef == null) continue;
            Procedure callee = getProgramScope().getProcedure(targetProcRef);
            if(callee == null) continue;
            String calleeSeg = callee.getSegmentCode();
            SegmentTypeInfo calleeType = getProgram().getSegmentType(calleeSeg);
            if(calleeType == null) continue;

            // Check 1: ROM_BANK to different ROM_BANK
            if(callerType.getKind() == SegmentTypeInfo.Kind.ROM_BANK && calleeType.getKind() == SegmentTypeInfo.Kind.ROM_BANK) {
               if(callerType.getBankId() != -1 && calleeType.getBankId() != -1 && callerType.getBankId() != calleeType.getBankId()) {
                  throw new CompileError("Illegal direct cross-bank call from '" + caller.getFullName() + "' (ROM_BANK " + callerType.getBankId() + ") to '" + callee.getFullName() + "' (ROM_BANK " + calleeType.getBankId() + "). Cross-bank calls must use a resident RAM trampoline.");
               }
            }
            // Check 2: RAM_OVERLAY to different RAM_OVERLAY
            if(callerType.getKind() == SegmentTypeInfo.Kind.RAM_OVERLAY && calleeType.getKind() == SegmentTypeInfo.Kind.RAM_OVERLAY) {
               if(callerType.getBankId() != -1 && calleeType.getBankId() != -1 && callerType.getBankId() != calleeType.getBankId()) {
                  throw new CompileError("Illegal direct cross-overlay call from '" + caller.getFullName() + "' (RAM_OVERLAY " + callerType.getBankId() + ") to '" + callee.getFullName() + "' (RAM_OVERLAY " + calleeType.getBankId() + "). Cross-overlay calls must use a resident RAM trampoline.");
               }
            }
         }
      }
      return false;
   }
}
