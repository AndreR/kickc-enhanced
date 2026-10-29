package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.asm.AsmChunk;
import dk.camelot64.cpufamily6502.CpuClobber;
import dk.camelot64.kickc.asm.AsmProgram;
import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.statements.*;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.values.LabelRef;
import dk.camelot64.kickc.model.values.RValue;
import dk.camelot64.kickc.model.values.ValueList;
import dk.camelot64.kickc.model.values.VariableRef;

import java.util.*;

/*** Ensures that no statement clobbers a CPU register used by an alive variable - and that assigning statements clobber the CPU registers they assign to */
public class Pass4AssertNoCpuClobber extends Pass2Base {

   public Pass4AssertNoCpuClobber(Program program) {
      super(program);
   }

   /**
    * Get all CPU registers clobbered by the ASM generated from a specific statement in the program
    *
    * @param clobber The clobber
    * @return The clobbered CPU registers
    */
   @SuppressWarnings("unchecked")
   private static final Collection<Registers.Register>[] CLOBBER_REGISTER_CACHE = new Collection[16];
   static {
      for(int i = 0; i < 16; i++) {
         List<Registers.Register> regs = new ArrayList<>(4);
         if((i & 1) != 0) regs.add(Registers.getRegisterA());
         if((i & 2) != 0) regs.add(Registers.getRegisterX());
         if((i & 4) != 0) regs.add(Registers.getRegisterY());
         if((i & 8) != 0) regs.add(Registers.getRegisterZ());
         CLOBBER_REGISTER_CACHE[i] = Collections.unmodifiableList(regs);
      }
   }

   public static Collection<Registers.Register> getClobberRegisters(CpuClobber clobber) {
      int key = (clobber.isRegisterA() ? 1 : 0)
              | (clobber.isRegisterX() ? 2 : 0)
              | (clobber.isRegisterY() ? 4 : 0)
              | (clobber.isRegisterZ() ? 8 : 0);
      return CLOBBER_REGISTER_CACHE[key];
   }

   /**
    * Check that no statement clobbers a CPU register used by an alive variable
    */
   public void check() {
      if(hasClobberProblem(true)) {
         throw new CompileError("CLOBBER ERROR! See log for more info.");
      }
   }

   /**
    * Determines whether any statement in the ASM program that clobbers a CPU register used by an alive variable
    *
    * @return true if there is a clobber problem in the program
    */
   public boolean hasClobberProblem(boolean verbose) {
      return hasClobberProblem(verbose, null);
   }

   public boolean hasClobberProblem(boolean verbose, AsmProgram asm) {
      if(asm == null) {
         asm = getProgram().getAsm();
      }
      boolean clobberProblem = false;
      for(AsmChunk asmChunk : asm.getChunks()) {
         if(asmChunk.getStatementIdx() != null) {
            // Find the ICL statement
            int statementIdx = asmChunk.getStatementIdx();
            Statement statement = getProgram().getStatementInfos().getStatement(statementIdx);
            // Find the registered clobbered by the ASM asmChunk
            CpuClobber asmChunkClobber = asmChunk.getClobber();
            Collection<Registers.Register> clobberRegisters = getClobberRegisters(asmChunkClobber);
            if(clobberRegisters.isEmpty()) {
               continue;
            }
            // Find vars assigned to in the statement
            Collection<VariableRef> assignedVars = Pass4RegisterUpliftPotentialRegisterAnalysis.getAssignedVars(statement);

            // Find alive variables
            Collection<VariableRef> aliveVars = getProgram().getLiveRangeVariablesEffective().getAliveEffective(statement);

            // If the chunk is an assignment in a phi transition, examine the later phi transition assignments and update alive variables alive and variables assigned
            if(statement instanceof StatementPhiBlock && asmChunk.getSubStatementId() != null && asmChunk.getSubStatementIdx() != null) {
               String phiTransitionId = asmChunk.getSubStatementId();
               int transitionAssignmentIdx = asmChunk.getSubStatementIdx();
               Graph.Block statementBlock = getProgram().getStatementInfos().getBlock(statementIdx);
               Map<LabelRef, PhiTransitions> programPhiTransitions = getProgram().getPhiTransitions();
               PhiTransitions phiTransitions = programPhiTransitions.get(statementBlock.getLabel());
               PhiTransitions.PhiTransition phiTransition = phiTransitions.getTransition(phiTransitionId);
               assignedVars = new LinkedHashSet<>(assignedVars);
               aliveVars = new ArrayList<>(aliveVars);
               for(PhiTransitions.PhiTransition.PhiAssignment phiAssignment : phiTransition.getAssignments()) {
                  if(phiAssignment.getAssignmentIdx() != transitionAssignmentIdx) {
                     // Only the current transition var is assigned
                     VariableRef assignedVar = phiAssignment.getVariable();
                     assignedVars.remove(assignedVar);
                  }
                  if(phiAssignment.getAssignmentIdx() > transitionAssignmentIdx) {
                     // For all assignments later than the current one - add referenced vars to alive
                     RValue rValue = phiAssignment.getrValue();
                     Collection<VariableRef> alive = VariableReferenceInfos.getReferencedVars(rValue);
                     aliveVars.addAll(alive);
                  }
               }
            }

            // If the chunk is an call parameter prepare, examine the later call parameter prepares and update alive variables 
            if(statement instanceof StatementCallPrepare && asmChunk.getSubStatementId() != null && asmChunk.getSubStatementIdx() != null) {
               int parameterIdx = asmChunk.getSubStatementIdx();
               final StatementCallPrepare callPrepare = (StatementCallPrepare) statement;
               final int numParameters = callPrepare.getNumParameters();
               aliveVars = new ArrayList<>(aliveVars);
               for(int idx = parameterIdx + 1; idx < numParameters; idx++) {
                  // For parameter prepares later than the current one - add referenced to alive
                  final RValue parameter = callPrepare.getParameter(idx);
                  Collection<VariableRef> alive = VariableReferenceInfos.getReferencedVars(parameter);
                  aliveVars.addAll(alive);
               }
            }

            // If the chunk is call finalize with a value list LValue, examine the LValue assigns and update alive variables
            if(statement instanceof StatementCallFinalize && asmChunk.getSubStatementId() != null && asmChunk.getSubStatementIdx() != null) {
               int memberIdx = asmChunk.getSubStatementIdx();
               final StatementCallFinalize stmtCallFinalize = (StatementCallFinalize) statement;
               final List<RValue> lValues = ((ValueList)stmtCallFinalize.getlValue()).getList();
               final int numLValues = lValues.size();
               assignedVars = new LinkedHashSet<>(assignedVars);
               for(int idx = 0; idx < numLValues; idx++) {
                  if(idx != memberIdx) {
                     // Only the current transition var is assigned
                     final VariableRef assignedVar = (VariableRef) lValues.get(idx);
                     assignedVars.remove(assignedVar);
                  }
               }
            }

            // If the chunk is call with a value list LValue, examine the later LValue assigns and update alive variables
            if(statement instanceof StatementReturn && asmChunk.getSubStatementId() != null && asmChunk.getSubStatementIdx() != null) {
               int memberIdx = asmChunk.getSubStatementIdx();
               final StatementReturn stmtReturn = (StatementReturn) statement;
               final List<RValue> returnValues = ((ValueList) stmtReturn.getValue()).getList();
               final int numReturnValues = returnValues.size();
               aliveVars = new ArrayList<>(aliveVars);
               for(int idx = memberIdx + 1; idx < numReturnValues; idx++) {
                  // Add all referenced vars in later return values to alive
                  final RValue returnValue = returnValues.get(idx);
                  Collection<VariableRef> alive = VariableReferenceInfos.getReferencedVars(returnValue);
                  aliveVars.addAll(alive);
               }
            }

            // Non-assigned alive variables must not be clobbered
            for(VariableRef aliveVar : aliveVars) {
               Variable variable = getProgram().getSymbolInfos().getVariable(aliveVar);
               Registers.Register aliveVarRegister = variable.getAllocation();
               if(aliveVarRegister.isMem()) {
                  // No need to check a zp-register - here we are only interested in CPU registers
                  continue;
               }
               if(assignedVars.contains(aliveVar)) {
                  // No need to register that is assigned by the statement - it will be clobbered
                  continue;
               }
               // Alive and not assigned to - clobber not allowed!
               if(clobberRegisters.contains(aliveVarRegister)) {
                  if(verbose) {
                     throw new CompileError(
                           "Error! Generated ASM has register clobber problem. " +
                                 "Alive variable " + aliveVar + " register " + aliveVarRegister + " clobbered by the ASM generated by statement " + statement.toString(getProgram(), true),
                           statement.getSource()
                     );
                  }
                  return true;
               }
            }
         }
      }
      return clobberProblem;
   }

}
