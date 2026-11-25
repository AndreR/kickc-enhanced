package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.iterator.ProgramValue;
import dk.camelot64.kickc.model.iterator.ProgramValueIterator;
import dk.camelot64.kickc.model.operators.Operator;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementAsm;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.statements.StatementCalling;
import dk.camelot64.kickc.model.statements.StatementConditionalJump;
import dk.camelot64.kickc.model.statements.StatementKickAsm;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeInference;
import dk.camelot64.kickc.model.values.LValue;
import dk.camelot64.kickc.model.values.PointerDereference;
import dk.camelot64.kickc.model.values.RValue;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.model.values.StructMemberRef;
import dk.camelot64.kickc.model.values.SymbolRef;
import dk.camelot64.kickc.model.values.VariableRef;
import dk.camelot64.kickc.passes.utils.AliasReplacer;

import dk.camelot64.kickc.passes.utils.StatementsBetween;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Compiler Pass simplifying conditional jumps that are simple comparisons
 */
public class Pass2ConditionalJumpSimplification extends Pass2SsaOptimization {

   public Pass2ConditionalJumpSimplification(Program program) {
      super(program);
   }

   @Override
   public boolean step() {
      final List<VariableRef> simpleConditionVars = new ArrayList<>();
      List<SimpleCondition> simpleConditions = getSimpleConditionTodos();
      for(SimpleCondition simpleCondition : simpleConditions) {
         if(rewriteSimpleCondition(simpleCondition)) {
            simpleConditionVars.add(simpleCondition.conditionVar);
         }
      }
      removeAssignments(getGraph(), simpleConditionVars);
      deleteSymbols(getProgramScope(), simpleConditionVars);
      return (simpleConditionVars.size() > 0);
   }

   /** An identified conditional jump with a one-variable condition that uses a comparison condition (less-than, greater-than, ...) and the assignment for that condition. */
   static class SimpleCondition {
      StatementConditionalJump conditionalJump;
      StatementAssignment conditionAssignment;
      VariableRef conditionVar;

      SimpleCondition(StatementConditionalJump conditionalJump, StatementAssignment conditionAssignment, VariableRef conditionVar) {
         this.conditionalJump = conditionalJump;
         this.conditionAssignment = conditionAssignment;
         this.conditionVar = conditionVar;
      }
   }

   /**
    * Find all simple conditions that can be rewritten.
    * Simple conditions are conditional jumps with a single variable as condition. The condition must be the result of an assignment with a comparison-operator.
    * @return The simple conditions to rewrite
    */
   private List<SimpleCondition> getSimpleConditionTodos() {
      final VariableReferenceInfos variableReferenceInfos = getProgram().getVariableReferenceInfos();
      List<SimpleCondition> todos = new ArrayList<>();
      for(var statement : getGraph().getAllStatements()) {
         if(statement instanceof StatementConditionalJump conditionalJump) {
            if(conditionalJump.getrValue1() == null && conditionalJump.getOperator() == null) {
               RValue conditionRValue = conditionalJump.getrValue2();
               if(conditionRValue instanceof VariableRef conditionVar) {
                  final Collection<Integer> conditionRvalueUsages = variableReferenceInfos.getVarUseStatements(conditionVar);
                  final Integer conditionDefineStmtIdx = variableReferenceInfos.getVarDefineStatement(conditionVar);
                  if(conditionRvalueUsages.size() == 1 && conditionDefineStmtIdx != null) {
                     final Statement conditionDefineStmt = getGraph().getStatementByIndex(conditionDefineStmtIdx);
                     if(conditionDefineStmt instanceof StatementAssignment conditionAssignment
                         && ((StatementAssignment) conditionDefineStmt).getOperator() != null) {
                        switch(conditionAssignment.getOperator().getOperator()) {
                           case "==":
                           case "<>":
                           case "!=":
                           case "<":
                           case ">":
                           case "<=":
                           case "=<":
                           case ">=":
                           case "=>":
                              todos.add(new SimpleCondition(conditionalJump, conditionAssignment, conditionVar));
                           default:
                        }
                     }
                  }
               }
            }
         }
      }
      return todos;
   }

   /**
    * Rewrite a simple condition - by inlining the comparison into the conditional jump.
    * If the condition uses any load/store-variables that are modified between the condition assignment and the jump the values of these is preserved in a new intermediate variable.
    *
    * @param simpleCondition The simple condition to rewrite
    */
   private boolean rewriteSimpleCondition(SimpleCondition simpleCondition) {
      final VariableReferenceInfos variableReferenceInfos = getProgram().getVariableReferenceInfos();
      // For each condition/assignment pair - do a rewrite to inline the condition if possible
      final Operator operator = simpleCondition.conditionAssignment.getOperator();
      RValue rValue1 = simpleCondition.conditionAssignment.getrValue1();
      RValue rValue2 = simpleCondition.conditionAssignment.getrValue2();

      boolean rValue1ReadsMem = hasPointerOrVolatileRead(getProgramScope(), rValue1);
      boolean rValue2ReadsMem = hasPointerOrVolatileRead(getProgramScope(), rValue2);
      boolean condReadsMemoryOrVolatile = rValue1ReadsMem || rValue2ReadsMem;
      Collection<Variable> referencedLoadStoreVariables = new LinkedHashSet<>(getReferencedLoadStoreVariables(rValue1));
      referencedLoadStoreVariables.addAll(getReferencedLoadStoreVariables(rValue2));

      // Fast path: ~90% of conditions use only SSA registers/constants and never need StatementsBetween traversal
      if(condReadsMemoryOrVolatile || !referencedLoadStoreVariables.isEmpty()) {
         final StatementInfos statementInfos = getProgram().getStatementInfos();
         final Graph.Block conditionDefineBlock = statementInfos.getBlock(simpleCondition.conditionAssignment);
         Collection<Statement> statementsBetween = StatementsBetween.find(simpleCondition.conditionAssignment, conditionDefineBlock, simpleCondition.conditionalJump, getGraph());

         if(!statementsBetween.isEmpty()) {
            // SSA-11: Do not sink pointer dereferences or volatile reads across intervening stores, calls, or inline asm.
            // Instead of aborting (which can leave un-synthesizable boolean comparison assignments for 24/32-bit types),
            // capture any memory/volatile operand into an intermediate right at the original condition assignment point.
            boolean hasSideEffectBetween = false;
            for(Statement statementBetween : statementsBetween) {
               if(hasMemoryOrSideEffect(getProgramScope(), statementBetween)) {
                  hasSideEffectBetween = true;
                  break;
               }
            }

            Statement insertAnchor = simpleCondition.conditionAssignment;
            final ScopeRef conditionDefineScopeRef = conditionDefineBlock.getScope();
            final Scope conditionDefineScope = getProgramScope().getScope(conditionDefineScopeRef);
            boolean replacedOperand = false;
            if(hasSideEffectBetween) {
               if(rValue1ReadsMem) {
                  SymbolType inferred1 = SymbolTypeInference.inferType(getProgramScope(), rValue1);
                  if(inferred1 == null || SymbolType.VOID.equals(inferred1)) {
                     getLog().append("Condition not sunk across side-effecting statement " + simpleCondition.conditionVar.toString(getProgram()));
                     return false;
                  }
                  SymbolType valType1 = inferred1.getQualified(false, false);
                  Variable tmpVar1 = VariableBuilder.createIntermediate(conditionDefineScope, valType1, getProgram());
                  StatementAssignment tmpAssign1 = new StatementAssignment(tmpVar1.getVariableRef(), rValue1, true, simpleCondition.conditionAssignment.getSource(), Comment.NO_COMMENTS);
                  conditionDefineBlock.addStatementAfter(tmpAssign1, insertAnchor);
                  insertAnchor = tmpAssign1;
                  rValue1 = tmpVar1.getRef();
                  replacedOperand = true;
                  getLog().append("Preserved condition rValue1 before side-effecting statement " + tmpAssign1.toString(getProgram(), false));
               }
               if(rValue2ReadsMem) {
                  SymbolType inferred2 = SymbolTypeInference.inferType(getProgramScope(), rValue2);
                  if(inferred2 == null || SymbolType.VOID.equals(inferred2)) {
                     getLog().append("Condition not sunk across side-effecting statement " + simpleCondition.conditionVar.toString(getProgram()));
                     return false;
                  }
                  SymbolType valType2 = inferred2.getQualified(false, false);
                  Variable tmpVar2 = VariableBuilder.createIntermediate(conditionDefineScope, valType2, getProgram());
                  StatementAssignment tmpAssign2 = new StatementAssignment(tmpVar2.getVariableRef(), rValue2, true, simpleCondition.conditionAssignment.getSource(), Comment.NO_COMMENTS);
                  conditionDefineBlock.addStatementAfter(tmpAssign2, insertAnchor);
                  insertAnchor = tmpAssign2;
                  rValue2 = tmpVar2.getRef();
                  replacedOperand = true;
                  getLog().append("Preserved condition rValue2 before side-effecting statement " + tmpAssign2.toString(getProgram(), false));
               }
            }

            if(replacedOperand) {
               referencedLoadStoreVariables = new LinkedHashSet<>(getReferencedLoadStoreVariables(rValue1));
               referencedLoadStoreVariables.addAll(getReferencedLoadStoreVariables(rValue2));
            }
            if(!referencedLoadStoreVariables.isEmpty()) {
               // Found referenced load/store variables: preserve each modified variable at most once
               for(Variable referencedLoadStoreVariable : referencedLoadStoreVariables) {
                  boolean mayBeModified = hasSideEffectBetween;
                  if(!mayBeModified) {
                     for(Statement statementBetween : statementsBetween) {
                        if(variableReferenceInfos.getDefinedVars(statementBetween).contains(referencedLoadStoreVariable.getVariableRef())) {
                           mayBeModified = true;
                           break;
                        }
                     }
                  }
                  if(mayBeModified) {
                     // A referenced load/store-variable may be modified in a statement between the assignment and the condition!
                     getLog().append("Condition not simple " + simpleCondition.conditionVar.toString(getProgram()) + " " + simpleCondition.conditionalJump.toString(getProgram(), false));
                     // Create an intermediate variable copy of the load/store-variable at the position of the condition-variable to preserve the value
                     SymbolType typeQualified = referencedLoadStoreVariable.getType().getQualified(false, referencedLoadStoreVariable.getType().isNomodify());
                     final Variable intermediateLoadStoreVar = VariableBuilder.createIntermediate(conditionDefineScope, typeQualified, getProgram());
                     final StatementAssignment intermediateLoadStoreAssignment = new StatementAssignment(intermediateLoadStoreVar.getVariableRef(), referencedLoadStoreVariable.getRef(), true, simpleCondition.conditionAssignment.getSource(), Comment.NO_COMMENTS);
                     conditionDefineBlock.addStatementAfter(intermediateLoadStoreAssignment, insertAnchor);
                     insertAnchor = intermediateLoadStoreAssignment;
                     // Replace all references to the load/store variable in the expressions with the new intermediate
                     final LinkedHashMap<SymbolRef, RValue> aliases = new LinkedHashMap<>();
                     aliases.put(referencedLoadStoreVariable.getRef(), intermediateLoadStoreVar.getRef());
                     {
                        final ProgramValue.GenericValue programValue1 = new ProgramValue.GenericValue(rValue1);
                        ProgramValueIterator.execute(programValue1, new AliasReplacer(aliases), null, null, null);
                        rValue1 = (RValue) programValue1.get();
                     }
                     {
                        final ProgramValue.GenericValue programValue2 = new ProgramValue.GenericValue(rValue2);
                        ProgramValueIterator.execute(programValue2, new AliasReplacer(aliases), null, null, null);
                        rValue2 = (RValue) programValue2.get();
                     }
                     getLog().append("Introduced intermediate condition variable " + intermediateLoadStoreAssignment.toString(getProgram(), false));
                  }
               }
            }
         }
      }
      // Perform the condition rewrite
      simpleCondition.conditionalJump.setrValue1(rValue1);
      simpleCondition.conditionalJump.setOperator(operator);
      simpleCondition.conditionalJump.setrValue2(rValue2);
      getLog().append("Simple Condition " + simpleCondition.conditionVar.toString(getProgram()) + " " + simpleCondition.conditionalJump.toString(getProgram(), false));
      return true;
   }

   public static boolean hasPointerOrVolatileRead(dk.camelot64.kickc.model.symbols.ProgramScope scope, RValue rValue) {
      if(rValue == null) return false;
      AtomicBoolean found = new AtomicBoolean(false);
      ProgramValueIterator.execute(new ProgramValue.GenericValue(rValue), (pv, stmt, stmtIt, block) -> {
         if(found.get()) return;
         if(pv.get() instanceof PointerDereference) {
            found.set(true);
         } else if(pv.get() instanceof VariableRef varRef) {
            Variable v = scope.getVariable(varRef);
            if(v != null && v.isVolatile()) {
               found.set(true);
            }
         }
      }, null, null, null);
      return found.get();
   }

   public static boolean hasMemoryOrSideEffect(dk.camelot64.kickc.model.symbols.ProgramScope scope, Statement stmt) {
      if(stmt instanceof StatementCalling || stmt instanceof StatementAsm || stmt instanceof StatementKickAsm) {
         return true;
      }
      if(stmt instanceof StatementAssignment assign) {
         LValue lv = assign.getlValue();
         if(lv instanceof PointerDereference || lv instanceof StructMemberRef) {
            return true;
         }
         if(lv instanceof VariableRef varRef) {
            Variable v = scope.getVariable(varRef);
            if(v != null && (v.isVolatile() || v.isKindLoadStore())) {
               return true;
            }
         }
         if(hasPointerOrVolatileRead(scope, assign.getrValue1()) || hasPointerOrVolatileRead(scope, assign.getrValue2())) {
            return true;
         }
      }
      return false;
   }

   /**
    * Get all referenced load/store variables in an RValue
    *
    * @param rValue The RValue
    * @return All referenced load/store variables
    */
   private Collection<Variable> getReferencedLoadStoreVariables(RValue rValue) {
      return VariableReferenceInfos.getReferencedVars(rValue)
            .stream()
            .map(variableRef -> getProgramScope().getVariable(variableRef))
            .filter(Variable::isKindLoadStore)
            .collect(Collectors.toList());
   }

}
