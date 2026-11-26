package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.StatementInfos;
import dk.camelot64.kickc.model.VariableReferenceInfos;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.values.VariableRef;
import dk.camelot64.kickc.passes.utils.StatementsBetween;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Compiler Pass simplifying unary not variables if they reference a comparison that can be inverted
 */
public class Pass2UnaryNotSimplification extends Pass2SsaOptimization {

   public Pass2UnaryNotSimplification(Program program) {
      super(program);
   }

   /**
    * Eliminate unary nots if they are the only usage of a reversible comparison
    */
   @Override
   public boolean step() {
      final List<VariableRef> unusedComparisons = optimizeUnaryNots();
      removeAssignments(getGraph(), unusedComparisons);
      deleteSymbols(getProgramScope(), unusedComparisons);
      return (unusedComparisons.size() > 0);
   }

   /**
    * Examine all unary nots. If they are the only usage of a reversible unary not replace the unary not with the reversed comparison - and eliminate the original variable.
    *
    * @return Unused comparisons (because they have been replaced with reversed comparisons)
    */
   private List<VariableRef> optimizeUnaryNots() {
      final VariableReferenceInfos variableReferenceInfos = getProgram().getVariableReferenceInfos();
      StatementInfos statementInfos = null;
      boolean refreshedStatementInfos = false;
      final List<VariableRef> unused = new ArrayList<>();
      for(var statement : getGraph().getAllStatements()) {
         if(statement instanceof StatementAssignment assignment) {
            if(assignment.getrValue1() == null
                  && assignment.getOperator() != null
                  && ("!".equals(assignment.getOperator().getOperator()) || "not".equals(assignment.getOperator().getOperator()))
                  && assignment.getrValue2() instanceof VariableRef
            ) {
               VariableRef tempVar = (VariableRef) assignment.getrValue2();
               final Integer tempVarDefineStmtIdx = variableReferenceInfos.getVarDefineStatement(tempVar);
               if(tempVarDefineStmtIdx != null) {
                  if(statementInfos == null) {
                     statementInfos = getProgram().getStatementInfos();
                  }
                  Statement tempVarDefineStmt = (statementInfos != null)
                        ? statementInfos.getStatement(tempVarDefineStmtIdx)
                        : getGraph().getStatementByIndex(tempVarDefineStmtIdx);
                  if(statementInfos != null && ((tempVarDefineStmt != null && !tempVarDefineStmtIdx.equals(tempVarDefineStmt.getIndex()))
                        || (tempVarDefineStmt == null && !refreshedStatementInfos))) {
                     getProgram().clearStatementInfos();
                     statementInfos = getProgram().getStatementInfos();
                     refreshedStatementInfos = true;
                     tempVarDefineStmt = (statementInfos != null)
                           ? statementInfos.getStatement(tempVarDefineStmtIdx)
                           : getGraph().getStatementByIndex(tempVarDefineStmtIdx);
                  }
                  if(tempVarDefineStmt instanceof StatementAssignment tempAssignment) {
                     int tempVarUsages = variableReferenceInfos.getVarUseStatements(tempVar).size();
                     if(tempVarUsages == 1 && tempAssignment.getOperator() != null && canSinkComparison(tempAssignment, assignment, variableReferenceInfos)) {
                        switch (tempAssignment.getOperator().getOperator()) {
                           case "<" -> {
                              createInverse(">=", assignment, tempAssignment);
                              unused.add(tempVar);
                           }
                           case ">" -> {
                              createInverse("<=", assignment, tempAssignment);
                              unused.add(tempVar);
                           }
                           case "<=", "=<" -> {
                              createInverse(">", assignment, tempAssignment);
                              unused.add(tempVar);
                           }
                           case ">=", "=>" -> {
                              createInverse("<", assignment, tempAssignment);
                              unused.add(tempVar);
                           }
                           case "==" -> {
                              createInverse("!=", assignment, tempAssignment);
                              unused.add(tempVar);
                           }
                           case "!=", "<>" -> {
                              createInverse("==", assignment, tempAssignment);
                              unused.add(tempVar);
                           }
                           case "!", "not" -> {
                              createInverse(null, assignment, tempAssignment);
                              unused.add(tempVar);
                           }
                        }
                     }
                  }
               }
            }
         }
      }
      return unused;
   }

   private boolean canSinkComparison(StatementAssignment tempAssignment, StatementAssignment assignment, VariableReferenceInfos variableReferenceInfos) {
      StatementInfos statementInfos = getProgram().getStatementInfos();
      Graph.Block defineBlock = statementInfos.getBlock(tempAssignment);
      if(defineBlock == null) return true;
      Collection<Statement> between = StatementsBetween.find(tempAssignment, defineBlock, assignment, getGraph());
      if(between.isEmpty()) return true;

      boolean readsMemOrVolatile =
            Pass2ConditionalJumpSimplification.hasPointerOrVolatileRead(getProgramScope(), tempAssignment.getrValue1())
                  || Pass2ConditionalJumpSimplification.hasPointerOrVolatileRead(getProgramScope(), tempAssignment.getrValue2());

      Collection<VariableRef> referencedVars = new ArrayList<>();
      if(tempAssignment.getrValue1() != null) referencedVars.addAll(VariableReferenceInfos.getReferencedVars(tempAssignment.getrValue1()));
      if(tempAssignment.getrValue2() != null) referencedVars.addAll(VariableReferenceInfos.getReferencedVars(tempAssignment.getrValue2()));

      boolean hasLoadStoreRef = false;
      for(VariableRef ref : referencedVars) {
         Variable v = getProgramScope().getVariable(ref);
         if(v != null && v.isKindLoadStore()) {
            hasLoadStoreRef = true;
            break;
         }
      }

      if(!readsMemOrVolatile && !hasLoadStoreRef) {
         return true;
      }

      for(Statement stmt : between) {
         if(Pass2ConditionalJumpSimplification.hasMemoryOrSideEffect(getProgramScope(), stmt)) {
            return false;
         }
         if(hasLoadStoreRef) {
            Collection<VariableRef> defined = variableReferenceInfos.getDefinedVars(stmt);
            for(VariableRef ref : referencedVars) {
               if(defined.contains(ref)) {
                  return false;
               }
            }
         }
      }
      return true;
   }

   /**
    * Updates an assignment to be the inverse of the temp assignment.
    *
    * @param newOperator The new operator to use
    * @param assignment The assignment to update
    * @param tempAssignment The temp assignment to take rValues from.
    */
   private void createInverse(String newOperator, StatementAssignment assignment, StatementAssignment tempAssignment) {
      assignment.setrValue1(tempAssignment.getrValue1());
      assignment.setOperator(newOperator == null ? null : Operators.getBinary(newOperator));
      assignment.setrValue2(tempAssignment.getrValue2());
      getLog().append("Inversing boolean not " + assignment.toString(getProgram(), false) + " from " + tempAssignment.toString(getProgram(), false));
   }


}
