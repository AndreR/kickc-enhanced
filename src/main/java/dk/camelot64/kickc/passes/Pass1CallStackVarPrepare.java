package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.CallGraph;
import dk.camelot64.kickc.model.Comment;
import dk.camelot64.kickc.model.ControlFlowBlock;
import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.iterator.ProgramValueIterator;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.values.LValue;
import dk.camelot64.kickc.model.values.ParamValue;
import dk.camelot64.kickc.model.values.ProcedureRef;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.model.values.Value;
import dk.camelot64.kickc.model.values.VariableRef;

import java.util.*;

/** Handle calling conventions {@link Procedure.CallingConvention#STACK_CALL} by converting parameters, return values and modified variables to load/store and  by adding parameter assignemtn statements to procedures*/
public class Pass1CallStackVarPrepare extends Pass2SsaOptimization {

   public Pass1CallStackVarPrepare(Program program) {
      super(program);
   }

   @Override
   public boolean step() {

      // Find all procedures that are STACK_CALL/VAR_CALL or transitively called by them
      Set<ProcedureRef> reentrantProcs = new LinkedHashSet<>();
      Queue<ProcedureRef> reentrantQueue = new ArrayDeque<>();
      for(Procedure procedure : getProgramScope().getAllProcedures(true)) {
         if(Procedure.CallingConvention.STACK_CALL.equals(procedure.getCallingConvention()) || Procedure.CallingConvention.VAR_CALL.equals(procedure.getCallingConvention())) {
            reentrantProcs.add(procedure.getRef());
            reentrantQueue.add(procedure.getRef());
         }
      }
      CallGraph callGraph = getProgram().getCallGraph();
      while(!reentrantQueue.isEmpty()) {
         ProcedureRef procRef = reentrantQueue.poll();
         CallGraph.CallBlock callBlock = callGraph.getCallBlock(procRef);
         if(callBlock != null) {
            for(ScopeRef calledScope : callBlock.getCalledBlocks()) {
               if(calledScope instanceof ProcedureRef) {
                  ProcedureRef calledProc = (ProcedureRef) calledScope;
                  if(!reentrantProcs.contains(calledProc)) {
                     reentrantProcs.add(calledProc);
                     reentrantQueue.add(calledProc);
                  }
               }
            }
         }
      }

      // Set variables modified in re-entrant call tree to load/store
      for(ProcedureRef procRef : reentrantProcs) {
         Procedure procedure = getProgramScope().getProcedure(procRef);
         if(procedure == null) continue;
         Set<VariableRef> modifiedVars = getProgram().getProcedureModifiedVars().getModifiedVars(procedure.getRef());
         if(modifiedVars != null) {
            for(VariableRef modifiedVar : modifiedVars) {
               final Variable variable = getProgramScope().getVariable(modifiedVar);
               if(variable != null && variable.isKindPhiMaster()) {
                  getLog().append("Converting variable modified inside re-entrant call tree procedure "+procedure.getFullName()+"() to load/store "+variable.toString(getProgram()));
                  variable.setKind(Variable.Kind.LOAD_STORE);
               }
            }
         }
      }

      // Set outer variables read inside re-entrant call tree to load/store
      for(ProcedureRef procRef : reentrantProcs) {
         Procedure procedure = getProgramScope().getProcedure(procRef);
         if(procedure == null) continue;
         List<Graph.Block> procBlocks = getGraph().getScopeBlocks(procedure.getRef());
         if(procBlocks != null) {
            for(Graph.Block block : procBlocks) {
               ProgramValueIterator.execute(block, (programValue, currentStmt, stmtIt, currentBlock) -> {
                  Value val = programValue.get();
                  if(val instanceof VariableRef) {
                     VariableRef varRef = (VariableRef) val;
                     String scopeNames = varRef.getScopeNames();
                     if(scopeNames == null || (!scopeNames.equals(procedure.getFullName()) && !scopeNames.startsWith(procedure.getFullName() + "::"))) {
                        Variable var = getProgramScope().getVariable(varRef);
                        if(var != null && var.isKindPhiMaster() && !var.isKindConstant()) {
                           var.setKind(Variable.Kind.LOAD_STORE);
                           getLog().append("Converting outer variable read inside re-entrant call tree procedure " + procedure.getFullName() + "() to load/store " + var.toString(getProgram()));
                        }
                     }
                  }
               });
            }
         }
      }

      // Set all parameter/return variables in VAR_CALL procedures to LOAD/STORE
      for(Procedure procedure : getProgramScope().getAllProcedures(true)) {
         if(Procedure.CallingConvention.VAR_CALL.equals(procedure.getCallingConvention())) {
            for(Variable parameter : procedure.getParameters()) {
               parameter.setKind(Variable.Kind.LOAD_STORE);
               getLog().append("Converting parameter in "+procedure.getCallingConvention().getName()+" procedure to load/store "+parameter.toString(getProgram()));
            }
            if(!SymbolType.VOID.equals(procedure.getReturnType())) {
               Variable returnVar = procedure.getLocalVariable("return");
               returnVar.setKind(Variable.Kind.LOAD_STORE);
               getLog().append("Converting return in "+procedure.getCallingConvention().getName()+" procedure to load/store "+returnVar.toString(getProgram()));
            }
         }
      }

      // Add parameter assignments at start of procedure in STACK_CALL procedures
      for(Procedure procedure : getProgramScope().getAllProcedures(true)) {
         if(procedure.isDeclaredIntrinsic()) continue;
         if(Procedure.CallingConvention.STACK_CALL.equals(procedure.getCallingConvention())) {
            final Graph.Block procedureBlock = getGraph().getBlock(procedure.getLabel().getRef());
            final ArrayList<Variable> params = new ArrayList<>(procedure.getParameters());
            Collections.reverse(params);
            for(Variable param : params) {
               final StatementAssignment paramAssignment = new StatementAssignment((LValue) param.getRef(), new ParamValue((VariableRef) param.getRef()), true, null, Comment.NO_COMMENTS);
               procedureBlock.getStatements().add(0, paramAssignment);
               getLog().append("Adding parameter assignment in "+procedure.getCallingConvention().getName()+" procedure "+paramAssignment.toString(getProgram(), false));
            }
         }
      }

      return false;
   }

}
