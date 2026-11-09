package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.iterator.ProgramValueIterator;
import dk.camelot64.kickc.model.operators.Operator;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.statements.StatementPhiBlock;
import dk.camelot64.kickc.model.symbols.Label;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.values.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loop-Invariant Code Motion (LICM).
 * Hoists pure computations out of natural loops into pre-headers when all operands are loop-invariant.
 */
public class Pass2LoopInvariantCodeMotion extends Pass2SsaOptimization {

   public Pass2LoopInvariantCodeMotion(Program program) {
      super(program);
   }

   @Override
   public boolean step() {
      NaturalLoopSet loopSet = getProgram().getLoopSet();
      if(loopSet == null) return false;
      VariableReferenceInfos varRefInfos = getProgram().getVariableReferenceInfos();
      StatementInfos statementInfos = getProgram().getStatementInfos();

      for(NaturalLoop loop : loopSet.getLoops()) {
         if(loop.getBlocks() == null || loop.getBlocks().isEmpty()) continue;
         if(optimizeLoop(loop, varRefInfos, statementInfos)) {
            getProgram().clearVariableReferenceInfos();
            getProgram().clearControlFlowBlockSuccessorClosure();
            getProgram().clearStatementInfos();
            getProgram().clearLoopSet();
            getProgram().clearDominators();
            return true;
         }
      }
      return false;
   }

   private boolean optimizeLoop(NaturalLoop loop, VariableReferenceInfos varRefInfos, StatementInfos statementInfos) {
      LabelRef headRef = loop.getHead();
      Graph.Block headBlock = getGraph().getBlock(headRef);
      if(headBlock == null) return false;

      List<Graph.Block> predecessors = getGraph().getPredecessors(headBlock);
      List<Graph.Block> entryPreds = new ArrayList<>();
      for(Graph.Block p : predecessors) {
         if(!loop.getBlocks().contains(p.getLabel())) {
            entryPreds.add(p);
         }
      }
      if(entryPreds.isEmpty()) return false;

      for(LabelRef blockRef : loop.getBlocks()) {
         Graph.Block block = getGraph().getBlock(blockRef);
         if(block == null) continue;

         for(Statement stmt : new ArrayList<>(block.getStatements())) {
            if(stmt instanceof StatementAssignment) {
               StatementAssignment assignment = (StatementAssignment) stmt;
               if(isHoistable(assignment, loop, headBlock, varRefInfos, statementInfos)) {
                  Graph.Block preheader = getOrCreatePreheader(loop, headBlock, entryPreds);
                  if(preheader == null) return false;

                  block.getStatements().remove(assignment);
                  preheader.getStatements().add(assignment);
                  getLog().append("LICM: Hoisted statement " + assignment.toString(getProgram(), false) + " out of loop " + headRef);
                  return true;
               }
            }
         }
      }
      return false;
   }

   private boolean isHoistable(StatementAssignment assignment, NaturalLoop loop, Graph.Block headBlock, VariableReferenceInfos varRefInfos, StatementInfos statementInfos) {
      if(!(assignment.getlValue() instanceof VariableRef)) return false;
      VariableRef lVarRef = (VariableRef) assignment.getlValue();
      Variable lVar = getProgramScope().getVariable(lVarRef);
      if(lVar == null || lVar.isVolatile() || lVar.isKindLoadStore()) return false;

      // Only hoist computations with operators (LICM targets invariant operations)
      // Bare variable-to-variable copies or constants do not save computation and risk moving call returns.
      Operator op = assignment.getOperator();
      if(op == null) {
         return false;
      }
      if(op != null) {
         if(Operators.DIVIDE.equals(op) || Operators.MODULO.equals(op)) {
            // Only safe if divisor is a non-zero constant
            if(assignment.getrValue2() instanceof ConstantInteger) {
               Long divVal = ((ConstantInteger) assignment.getrValue2()).getInteger();
               if(divVal == 0) return false;
            } else {
               return false;
            }
         } else if(!Operators.PLUS.equals(op) &&
                   !Operators.MINUS.equals(op) &&
                   !Operators.SHIFT_LEFT.equals(op) &&
                   !Operators.SHIFT_RIGHT.equals(op) &&
                   !Operators.BOOL_AND.equals(op) &&
                   !Operators.BOOL_OR.equals(op) &&
                   !Operators.BOOL_XOR.equals(op) &&
                   !Operators.MULTIPLY.equals(op) &&
                   !Operators.NEG.equals(op) &&
                   !Operators.POS.equals(op) &&
                   !Operators.BOOL_NOT.equals(op) &&
                   !Operators.LOGIC_NOT.equals(op) &&
                   !Operators.BYTE0.equals(op) &&
                   !Operators.BYTE1.equals(op) &&
                   !Operators.WORD0.equals(op) &&
                   !Operators.WORD1.equals(op)) {
            return false;
         }
      }

      // Check for volatile or memory reads
      if(isVolatile(assignment)) return false;

      // Must not be used as back-edge value in loop head phi block
      if(headBlock.hasPhiBlock()) {
         for(StatementPhiBlock.PhiVariable phiVar : headBlock.getPhiBlock().getPhiVariables()) {
            for(StatementPhiBlock.PhiRValue phiVal : phiVar.getValues()) {
               if(lVarRef.equals(phiVal.getrValue())) {
                  return false;
               }
            }
         }
      }

      // Check that all operands are defined outside the loop
      Collection<VariableRef> usedVars = varRefInfos.getUsedVars(assignment);
      if(usedVars.isEmpty() && assignment.getrValue1() == null) return false;
      for(VariableRef usedVar : usedVars) {
         // Procedure return variables are dynamic results of calls and must never be hoisted
         if(usedVar.getFullName().endsWith("::return") || usedVar.getFullName().contains("return")) {
            return false;
         }
         Integer defIdx = varRefInfos.getVarDefineStatement(usedVar);
         if(defIdx != null) {
            LabelRef defBlock = statementInfos.getBlockRef(defIdx);
            if(defBlock != null && loop.getBlocks().contains(defBlock)) {
               // Operand defined inside loop -> not invariant
               return false;
            }
         } else {
            // Variable defined outside or LOAD_STORE variable: verify it is not modified anywhere in the loop
            if(isVariableModifiedInLoop(usedVar, loop)) {
               return false;
            }
         }
      }

      return true;
   }

   private boolean isVariableModifiedInLoop(VariableRef varRef, NaturalLoop loop) {
      dk.camelot64.kickc.model.ProcedureModifiedVars procModVars = getProgram().getProcedureModifiedVars();
      for(LabelRef blockRef : loop.getBlocks()) {
         Graph.Block block = getGraph().getBlock(blockRef);
         if(block == null) continue;
         for(Statement stmt : block.getStatements()) {
            if(stmt instanceof StatementAssignment) {
               LValue lVal = ((StatementAssignment) stmt).getlValue();
               if(varRef.equals(lVal)) return true;
            } else if(stmt instanceof dk.camelot64.kickc.model.statements.StatementCall) {
               ProcedureRef callee = ((dk.camelot64.kickc.model.statements.StatementCall) stmt).getProcedure();
               if(varRef.getFullName().startsWith(callee.getFullName() + "::")) return true;
               if(procModVars != null) {
                  Set<VariableRef> mods = procModVars.getModifiedVars(callee);
                  if(mods != null && mods.contains(varRef)) return true;
               } else {
                  return true;
               }
            } else if(stmt instanceof dk.camelot64.kickc.model.statements.StatementCallExecute) {
               ProcedureRef callee = ((dk.camelot64.kickc.model.statements.StatementCallExecute) stmt).getProcedure();
               if(varRef.getFullName().startsWith(callee.getFullName() + "::")) return true;
               if(procModVars != null) {
                  Set<VariableRef> mods = procModVars.getModifiedVars(callee);
                  if(mods != null && mods.contains(varRef)) return true;
               } else {
                  return true;
               }
            } else if(stmt instanceof dk.camelot64.kickc.model.statements.StatementAsm) {
               return true; // Inline assembly might modify memory
            }
         }
      }
      return false;
   }

   private Graph.Block getOrCreatePreheader(NaturalLoop loop, Graph.Block headBlock, List<Graph.Block> entryPreds) {
      if(entryPreds.size() == 1) {
         Graph.Block singlePred = entryPreds.get(0);
         if(singlePred.getConditionalSuccessor() == null && loop.getHead().equals(singlePred.getDefaultSuccessor())) {
            return singlePred;
         }
      }

      ScopeRef scopeRef = headBlock.getScope();
      Scope scope = getProgramScope().getScope(scopeRef);
      Label preheaderLabel = scope.addLabelIntermediate();
      ControlFlowBlock preheaderBlock = new ControlFlowBlock(preheaderLabel.getRef(), scopeRef);
      preheaderBlock.setDefaultSuccessor(loop.getHead());
      getGraph().addBlock(preheaderBlock);

      for(Graph.Block ep : entryPreds) {
         if(loop.getHead().equals(ep.getDefaultSuccessor())) {
            ep.setDefaultSuccessor(preheaderBlock.getLabel());
         }
         if(loop.getHead().equals(ep.getConditionalSuccessor())) {
            ep.setConditionalSuccessor(preheaderBlock.getLabel());
         }
      }

      if(headBlock.hasPhiBlock()) {
         StatementPhiBlock phiBlock = headBlock.getPhiBlock();
         for(StatementPhiBlock.PhiVariable phiVar : phiBlock.getPhiVariables()) {
            for(StatementPhiBlock.PhiRValue phiVal : phiVar.getValues()) {
               for(Graph.Block ep : entryPreds) {
                  if(ep.getLabel().equals(phiVal.getPredecessor())) {
                     phiVal.setPredecessor(preheaderBlock.getLabel());
                  }
               }
            }
         }
      }

      return preheaderBlock;
   }

   private boolean isVolatile(Statement statement) {
      AtomicBoolean isVol = new AtomicBoolean(false);
      ProgramValueIterator.execute(statement, (programValue, currentStmt, stmtIt, currentBlock) -> {
         Value val = programValue.get();
         if(val instanceof PointerDereference || val instanceof PointerDereferenceIndexed) {
            isVol.set(true);
         }
         if(val instanceof VariableRef) {
            Variable variable = getProgramScope().getVariable((VariableRef) val);
            if(variable != null && variable.isVolatile()) {
               isVol.set(true);
            }
         }
      }, null, null);
      return isVol.get();
   }

}
