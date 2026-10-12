package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.iterator.ProgramValue;
import dk.camelot64.kickc.model.iterator.ProgramValueHandler;
import dk.camelot64.kickc.model.iterator.ProgramValueIterator;
import dk.camelot64.kickc.model.operators.Operator;
import dk.camelot64.kickc.model.operators.OperatorCast;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeInference;
import dk.camelot64.kickc.model.values.*;

import java.lang.InternalError;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Compiler Pass identifying R-values that are duplicates of each other - and consolidates them to a single RValue.
 */
public class Pass2DuplicateRValueIdentification extends Pass2SsaOptimization {

   public Pass2DuplicateRValueIdentification(Program program) {
      super(program);
   }

   private static record RValueKey(RValue rValue1, Operator operator, RValue rValue2) {}

   @Override
   public boolean step() {
      boolean modified = false;

      // Dominators are used for determining if one statement is guaranteed to be executed before another statement
      DominatorsGraph dominators = getProgram().getDominators();

      // All RValues in the program indexed by their signature
      Map<RValueKey, List<AssignmentWithRValue>> rValues = new HashMap<>();

      for(var block : getGraph().getAllBlocks()) {
         for(Statement statement : block.getStatements()) {
            if(statement instanceof StatementAssignment) {
               StatementAssignment thisAssignment = (StatementAssignment) statement;
               Operator op = thisAssignment.getOperator();
               if(op == null || op instanceof OperatorCast) continue;
               if(Operators.LE.equals(op) || Operators.LT.equals(op) || Operators.GE.equals(op) || Operators.GT.equals(op)
                     || Operators.EQ.equals(op) || Operators.NEQ.equals(op) || Operators.LOGIC_NOT.equals(op)
                     || Operators.LOGIC_AND.equals(op) || Operators.LOGIC_OR.equals(op)
                     || Operators.BYTE0.equals(op) || Operators.BYTE1.equals(op) || Operators.BYTE2.equals(op) || Operators.BYTE3.equals(op)
                     || Operators.WORD0.equals(op) || Operators.WORD1.equals(op)) {
                  continue;
               }
               RValue rVal1 = thisAssignment.getrValue1();
               RValue rVal2 = thisAssignment.getrValue2();
               if((rVal1 == null || rVal1 instanceof ConstantValue) && (rVal2 == null || rVal2 instanceof ConstantValue)) {
                  continue;
               }
               AssignmentWithRValue thisRValue = new AssignmentWithRValue(thisAssignment, block);
               if(!thisRValue.isConstant() && !thisRValue.isTrivial() && !thisRValue.isVolatile() && !thisRValue.isLoadStore()) {
                  boolean duplicate = false;
                  RValueKey key = new RValueKey(thisRValue.rValue1, thisRValue.operator, thisRValue.rValue2);
                  List<AssignmentWithRValue> candidates = rValues.get(key);
                  if(candidates != null) {
                     for(AssignmentWithRValue otherRValue : candidates) {
                        if(otherRValue.isSameRValue(thisRValue)) {
                           // Check if any of the statements is guaranteed to be executed before the other statement
                           DominatorsBlock thisDominators = dominators.getDominators(thisRValue.block.getLabel());
                           DominatorsBlock otherDominators = dominators.getDominators(otherRValue.block.getLabel());
                           if(otherRValue.block.getLabel().equals(thisRValue.block.getLabel())) {
                              // The two assignments are in the same block
                              // Since we are visiting statements sequentially the other  other assignment is guaranteed to be executed before this assignment
                              duplicate |= deduplicateRValue(otherRValue, thisRValue);
                           } else if(thisDominators.contains(otherRValue.block.getLabel())) {
                              // The other assignment is guaranteed to be executed before this assignment
                              if(scopeMatch(thisRValue, otherRValue))
                                 duplicate |= deduplicateRValue(otherRValue, thisRValue);
                           } else if(otherDominators.contains(thisRValue.block.getLabel())) {
                              // This assignment is guaranteed to be executed before the other assignment
                              if(scopeMatch(thisRValue, otherRValue))
                                 duplicate |= deduplicateRValue(thisRValue, otherRValue);
                           }
                        }
                     }
                  }
                  // If not a duplicate itself - add it to the candidate pool
                  if(!duplicate)
                     rValues.computeIfAbsent(key, k -> new ArrayList<>()).add(thisRValue);
                  // Determine if anything is modified
                  modified |= duplicate;
               }
            }
         }
      }
      return modified;
   }

   /**
    * Check that the scopes match for all involved variables.
    *
    * @param thisRValue This RValue
    * @param otherRValue This duplicate RValue
    * @return true if all scopes match
    */
   private boolean scopeMatch(AssignmentWithRValue thisRValue, AssignmentWithRValue otherRValue) {
      if(!thisRValue.block.getScope().equals(otherRValue.block.getScope()))
         return false;
      ScopeRef scope = thisRValue.block.getScope();
      return
            scopeMatch(thisRValue.assignment.getlValue(), scope) &&
                  scopeMatch(otherRValue.assignment.getlValue(), scope) &&
                  scopeMatch(thisRValue.rValue1, scope) &&
                  scopeMatch(thisRValue.rValue2, scope);
   }

   /**
    * Check that the scope of the values inside an RValue matches the scope of a block.
    * We only know that a specific variable version has the same value if it is from the same scope as the block.
    * Otherwise it might be modified inside some called function.
    *
    * @param value The RValue to examine (may be null)
    * @param block The block to examine
    * @return true if the scope match
    */
   private boolean scopeMatch(Value value, ScopeRef scopeRef) {
      if(value == null)
         return true;
      List<SymbolVariableRef> varRefs = new ArrayList<>();
      ProgramValueIterator.execute(new ProgramValue.GenericValue(value),
            (programValue, currentStmt, stmtIt, currentBlock) -> {
               if(programValue.get() instanceof SymbolVariableRef) varRefs.add((SymbolVariableRef) programValue.get());
            }
            , null, null, null);
      for(SymbolVariableRef varRef : varRefs) {
         Variable var = getProgramScope().getVariable(varRef);
         if(!var.getScope().getRef().equals(scopeRef))
            return false;
      }
      return true;
   }

   /**
    * De-duplicates the RValues of two assignments, where the first one is guaranteed to be called before the second one.
    * Modifies the second assignment to reference the result of the first.
    *
    * @param firstRValue The first assignment with the RValue - guaranteed to be called before the second assignment.
    * @param secondRValue A second assignment with a duplicated RValue.
    * @return true if the deduplication was successful.
    */
   private boolean deduplicateRValue(AssignmentWithRValue firstRValue, AssignmentWithRValue secondRValue) {
      if(firstRValue.assignment.getlValue() instanceof VariableRef) {
         StatementAssignment secondAssignment = secondRValue.assignment;
         getLog().append("Identified duplicate assignment right side " + secondAssignment.toString(getProgram(), false));
         secondAssignment.setrValue1(null);
         secondAssignment.setOperator(null);
         secondAssignment.setrValue2(firstRValue.assignment.getlValue());
         return true;
      } else {
         throw new InternalError("Complex lValue for duplicate rvalue " + firstRValue.assignment.toString(getProgram(), false));
      }
   }


   /**
    * Represents an RValue of an assignment.
    */
   private class AssignmentWithRValue {
      private Graph.Block block;
      private StatementAssignment assignment;
      private RValue rValue1;
      private Operator operator;
      private RValue rValue2;

      public AssignmentWithRValue(StatementAssignment assignment, Graph.Block block) {
         this.block = block;
         this.assignment = assignment;
         this.rValue1 = assignment.getrValue1();
         this.operator = assignment.getOperator();
         this.rValue2 = assignment.getrValue2();
      }

      private boolean isConstant() {
         if(rValue1 != null && !(rValue1 instanceof ConstantValue))
            return false;
         if(rValue2 != null && !(rValue2 instanceof ConstantValue))
            return false;
         return true;
      }

      private boolean isLoadStore() {
         return isKindLoadStore(rValue1) || isKindLoadStore(rValue2);
      }

      private boolean isKindLoadStore(Value val) {
         if(val == null) return false;
         if(val instanceof VariableRef) {
            Variable var = getProgramScope().getVar((VariableRef) val);
            return var != null && var.isKindLoadStore();
         }
         if(val instanceof CastValue) {
            return isKindLoadStore(((CastValue) val).getValue());
         }
         return false;
      }

      private boolean isVolatile() {
         if(Operators.DEREF.equals(operator) || Operators.DEREF_IDX.equals(operator))
            return true;
         return isVolatileVal(rValue1) || isVolatileVal(rValue2);
      }

      private boolean isVolatileVal(Value val) {
         if(val == null) return false;
         if(val instanceof PointerDereference) return true;
         if(val instanceof VariableRef) {
            Variable variable = getProgramScope().getVariable((VariableRef) val);
            return variable != null && variable.isVolatile();
         }
         if(val instanceof CastValue) {
            return isVolatileVal(((CastValue) val).getValue());
         }
         return false;
      }

      private boolean isTrivial() {
         if(operator == null) return true;
         if(operator instanceof OperatorCast) return true;
         if(operator.equals(Operators.LE)) return true;
         if(operator.equals(Operators.LT)) return true;
         if(operator.equals(Operators.GE)) return true;
         if(operator.equals(Operators.GT)) return true;
         if(operator.equals(Operators.EQ)) return true;
         if(operator.equals(Operators.NEQ)) return true;
         if(operator.equals(Operators.LOGIC_NOT)) return true;
         if(operator.equals(Operators.LOGIC_AND)) return true;
         if(operator.equals(Operators.LOGIC_OR)) return true;
         if(operator.equals(Operators.BYTE0)) return true;
         if(operator.equals(Operators.BYTE1)) return true;
         if(operator.equals(Operators.BYTE2)) return true;
         if(operator.equals(Operators.BYTE3)) return true;
         if(operator.equals(Operators.WORD0)) return true;
         if(operator.equals(Operators.WORD1)) return true;
         if(operator.equals(Operators.PLUS) && rValue2 instanceof ConstantValue) {
            final SymbolType type1 = inferValueType(rValue1);
            return type1 != null && type1.getSizeBytes() == 1;
         }
         if(operator.equals(Operators.PLUS) && rValue1 instanceof ConstantValue) {
            final SymbolType type2 = inferValueType(rValue2);
            return type2 != null && type2.getSizeBytes() == 1;
         }
         return false;
      }

      private SymbolType inferValueType(RValue rValue) {
         if(rValue instanceof VariableRef) {
            Variable var = getProgramScope().getVariable((VariableRef) rValue);
            if(var != null) return var.getType();
         }
         return SymbolTypeInference.inferType(getProgramScope(), rValue);
      }


      /**
       * Determines if two assignment RValues are duplicates.
       *
       * @param that The other assignment
       * @return true if the RValues are duplicates
       */
      public boolean isSameRValue(AssignmentWithRValue that) {
         return Objects.equals(rValue1, that.rValue1) &&
               Objects.equals(operator, that.operator) &&
               Objects.equals(rValue2, that.rValue2);
      }

      @Override
      public boolean equals(Object o) {
         if(this == o) return true;
         if(o == null || getClass() != o.getClass()) return false;
         AssignmentWithRValue that = (AssignmentWithRValue) o;
         return block.equals(that.block) &&
               assignment.equals(that.assignment) &&
               Objects.equals(rValue1, that.rValue1) &&
               Objects.equals(operator, that.operator) &&
               Objects.equals(rValue2, that.rValue2);
      }

      @Override
      public int hashCode() {
         return Objects.hash(block, assignment, rValue1, operator, rValue2);
      }
   }
}
