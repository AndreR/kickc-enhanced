package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.statements.*;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeInference;
import dk.camelot64.kickc.model.types.SymbolTypeProcedure;
import dk.camelot64.kickc.model.values.*;
import dk.camelot64.kickc.passes.calcs.PassNCalcVariableReferenceInfos;

import java.util.*;

/** Handle calling conventions {@link Procedure.CallingConvention#STACK_CALL} {@link Procedure.CallingConvention#VAR_CALL} by converting to call-prepare, call-execute, call-finalize */
public class Pass1CallStackVarConvert extends Pass2SsaOptimization {

   public Pass1CallStackVarConvert(Program program) {
      super(program);
   }

   @Override
   public boolean step() {

      // Transform STACK_CALL/VAR_CALL calls to call-prepare, call-execute, call-finalize
      for(var block : getGraph().getAllBlocks()) {
         ListIterator<Statement> stmtIt = block.getStatements().listIterator();
         while(stmtIt.hasNext()) {
            Statement statement = stmtIt.next();
            if(statement instanceof StatementCall) {
               StatementCall call = (StatementCall) statement;
               ProcedureRef procedureRef = call.getProcedure();
               Procedure procedure = getProgramScope().getProcedure(procedureRef);
               Procedure.CallingConvention callingConvention = procedure.getCallingConvention();
               if(Procedure.CallingConvention.STACK_CALL.equals(callingConvention) || Procedure.CallingConvention.VAR_CALL.equals(callingConvention)) {
                  boolean hasParamOrReturn = (call.getParameters().size() > 0) || !SymbolType.VOID.equals(procedure.getReturnType());

                  Scope blockScope = getProgramScope().getScope(block.getScope());
                  Procedure fromProcedure = (blockScope instanceof Procedure) ? (Procedure) blockScope : null;
                  boolean isRecursive = false;
                  if(fromProcedure != null && Procedure.CallingConvention.STACK_CALL.equals(callingConvention)) {
                     CallGraph callGraph = getProgram().getCallGraph();
                     if(fromProcedure.equals(procedure)
                           || (callGraph.getRecursiveCalls(procedure.getRef()) != null && callGraph.getRecursiveCalls(procedure.getRef()).contains(fromProcedure.getRef()))
                           || (callGraph.getRecursiveCalls(fromProcedure.getRef()) != null && callGraph.getRecursiveCalls(fromProcedure.getRef()).contains(procedure.getRef()))) {
                        isRecursive = true;
                     }
                  }

                  List<Variable> spilledVars = new ArrayList<>();
                  if(isRecursive) {
                     Collection<Variable> candidates = fromProcedure.getAllVariables(true);
                     for(Variable candidate : candidates) {
                        if(candidate.isKindConstant()) continue;
                        if(candidate.getMemoryAddress() != null) continue;
                        if(candidate.getType() == null || SymbolType.VOID.equals(candidate.getType())) continue;
                        if(call.getlValue() != null && call.getlValue().equals(candidate.getRef())) continue;

                        if(!isVariableDefinedBefore(candidate.getVariableRef(), block, call, fromProcedure)) {
                           continue;
                        }
                        if(isVariableLiveAfter(candidate.getVariableRef(), block, call, fromProcedure)) {
                           spilledVars.add(candidate);
                        }
                     }
                     spilledVars.sort(Comparator.comparing(Variable::getFullName));

                     long spilledBytes = 0;
                     for(Variable var : spilledVars) {
                        if(var.getType() != null) {
                           spilledBytes += var.getType().getSizeBytes();
                        }
                     }
                     if(spilledBytes > 64) {
                        getLog().append("Warning: Recursive call in " + fromProcedure.getFullName() + " spills " + spilledBytes + " bytes to the stack (exceeds recommended 64 bytes).");
                     }
                     long paramBytes = CallingConventionStack.getParametersByteSize(procedure.getType());
                     long returnBytes = (procedure.getReturnType() == null || SymbolType.VOID.equals(procedure.getReturnType())) ? 0 : procedure.getReturnType().getSizeBytes();
                     long totalFrameBytes = spilledBytes + paramBytes + returnBytes + 2;
                     if(totalFrameBytes > 128) {
                        throw new CompileError("Recursive call in " + fromProcedure.getFullName() + " has total stack frame size of " + totalFrameBytes + " bytes, exceeding the 128-byte limit for recursion.", call);
                     }
                  }

                  stmtIt.remove();

                  // 1. Push spilled variables onto stack (before StatementCallPrepare)
                  for(Variable v : spilledVars) {
                     v.setKind(Variable.Kind.LOAD_STORE);
                     stmtIt.add(new StatementAssignment(new StackPushValue(v.getType()), v.getRef(), false, call.getSource(), Comment.NO_COMMENTS));
                     getLog().append("Recursion spilling: push " + v.getFullName() + " before call " + procedure.getFullName());
                  }

                  // 2. Call Prepare, Execute, Finalize
                  stmtIt.add(new StatementCallPrepare(procedure.getType(), procedureRef, call.getParameters(), callingConvention, call.getSource(), hasParamOrReturn?call.getComments():Comment.NO_COMMENTS));
                  stmtIt.add(new StatementCallExecute(procedure.getType(), procedureRef, callingConvention, call.getSource(), hasParamOrReturn?Comment.NO_COMMENTS:call.getComments()));
                  stmtIt.add(new StatementCallFinalize(call.getlValue(), procedure.getType(), procedureRef, callingConvention, call.getSource(), Comment.NO_COMMENTS));

                  // 3. Pull spilled variables in reverse order after CallFinalize
                  for(int i = spilledVars.size() - 1; i >= 0; i--) {
                     Variable v = spilledVars.get(i);
                     stmtIt.add(new StatementAssignment((LValue) v.getRef(), new StackPullValue(v.getType()), false, call.getSource(), Comment.NO_COMMENTS));
                     getLog().append("Recursion spilling: pull " + v.getFullName() + " after call " + procedure.getFullName());
                  }

                  getLog().append("Calling convention " + callingConvention.getName() + " adding prepare/execute/finalize for " + call.toString(getProgram(), false));
               }
            } else if(statement instanceof StatementCallPointer) {
               StatementCallPointer call = (StatementCallPointer) statement;
               boolean hasParamOrReturn = call.getNumParameters() > 0 || call.getlValue() != null;
               final SymbolType procType = SymbolTypeInference.inferType(getProgramScope(), call.getProcedure());
               if(!(procType instanceof SymbolTypeProcedure)) {
                  throw new CompileError("Called object is not a function or function pointer "+call.getProcedure().toString(), call);
               }
               SymbolTypeProcedure procedureType = (SymbolTypeProcedure) procType;
               stmtIt.remove();
               stmtIt.add(new StatementCallPrepare(procedureType, null, call.getParameters(), Procedure.CallingConvention.STACK_CALL, call.getSource(), hasParamOrReturn?call.getComments():Comment.NO_COMMENTS));
               stmtIt.add(new StatementCallExecute(procedureType, call.getProcedure(), Procedure.CallingConvention.STACK_CALL, call.getSource(), hasParamOrReturn?Comment.NO_COMMENTS:call.getComments()));
               stmtIt.add(new StatementCallFinalize(call.getlValue(), procedureType, null, Procedure.CallingConvention.STACK_CALL, call.getSource(), Comment.NO_COMMENTS));
               getLog().append("Calling convention " + Procedure.CallingConvention.STACK_CALL + " adding prepare/execute/finalize for " + call.toString(getProgram(), false));
            }

         }
      }

      return false;
   }

   private boolean isVariableDefinedBefore(VariableRef varRef, Graph.Block callBlock, StatementCall call, Procedure procedure) {
      Variable var = getProgramScope().getVariable(varRef);
      if(procedure.getParameters().contains(var)) {
         return true;
      }

      for(Statement stmt : callBlock.getStatements()) {
         if(stmt.equals(call)) {
            break;
         }
         if(PassNCalcVariableReferenceInfos.getDefinedVars(stmt).contains(varRef)) {
            return true;
         }
      }

      Set<LabelRef> visited = new HashSet<>();
      Queue<Graph.Block> queue = new ArrayDeque<>();
      for(Graph.Block pred : getGraph().getPredecessors(callBlock)) {
         queue.add(pred);
      }

      while(!queue.isEmpty()) {
         Graph.Block block = queue.poll();
         if(!visited.add(block.getLabel())) continue;

         Scope blockScope = getProgramScope().getScope(block.getScope());
         if(!procedure.equals(blockScope)) continue;

         for(Statement stmt : block.getStatements()) {
            if(PassNCalcVariableReferenceInfos.getDefinedVars(stmt).contains(varRef)) {
               return true;
            }
         }

         for(Graph.Block pred : getGraph().getPredecessors(block)) {
            if(!visited.contains(pred.getLabel())) {
               queue.add(pred);
            }
         }
      }

      return false;
   }

   private boolean isVariableLiveAfter(VariableRef varRef, Graph.Block callBlock, StatementCall call, Procedure procedure) {
      boolean afterCall = false;
      for(Statement stmt : callBlock.getStatements()) {
         if(!afterCall) {
            if(stmt.equals(call)) {
               afterCall = true;
            }
            continue;
         }
         if(PassNCalcVariableReferenceInfos.getUsedVars(stmt).contains(varRef)) {
            return true;
         }
         if(PassNCalcVariableReferenceInfos.getDefinedVars(stmt).contains(varRef)) {
            return false;
         }
      }

      Set<LabelRef> visited = new HashSet<>();
      Queue<LabelRef> queue = new ArrayDeque<>();
      for(LabelRef succ : callBlock.getSuccessors()) {
         queue.add(succ);
      }

      while(!queue.isEmpty()) {
         LabelRef blockLabel = queue.poll();
         if(!visited.add(blockLabel)) continue;

         Graph.Block block = getGraph().getBlock(blockLabel);
         if(block == null) continue;
         Scope blockScope = getProgramScope().getScope(block.getScope());
         if(!procedure.equals(blockScope)) continue;

         boolean killed = false;
         for(Statement stmt : block.getStatements()) {
            if(PassNCalcVariableReferenceInfos.getUsedVars(stmt).contains(varRef)) {
               return true;
            }
            if(PassNCalcVariableReferenceInfos.getDefinedVars(stmt).contains(varRef)) {
               killed = true;
               break;
            }
         }

         if(!killed) {
            for(LabelRef succ : block.getSuccessors()) {
               if(!visited.contains(succ)) {
                  queue.add(succ);
               }
            }
         }
      }

      return false;
   }

}
