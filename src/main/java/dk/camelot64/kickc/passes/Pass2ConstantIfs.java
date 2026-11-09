package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.ConstantNotLiteral;
import dk.camelot64.kickc.model.ControlFlowBlock;
import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.operators.Operator;
import dk.camelot64.kickc.model.operators.OperatorBinary;
import dk.camelot64.kickc.model.operators.OperatorUnary;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementConditionalJump;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeInference;
import dk.camelot64.kickc.model.types.SymbolTypeIntegerFixed;
import dk.camelot64.kickc.model.values.*;
import dk.camelot64.kickc.model.DominatorsBlock;
import dk.camelot64.kickc.model.DominatorsGraph;
import dk.camelot64.kickc.model.symbols.Variable;

import java.util.List;
import java.util.ListIterator;
import java.util.Objects;

/**
 * Compiler Pass identifying constant if() conditions
 */
public class Pass2ConstantIfs extends Pass2SsaOptimization {

   public Pass2ConstantIfs(Program program) {
      super(program);
   }

   /**
    * Identify constant conditions in if()'s
    *
    * @return true optimization was performed. false if no optimization was possible.
    */
   @Override
   public boolean step() {
      boolean modified = false;

      for(var block : getGraph().getAllBlocks()) {
         ListIterator<Statement> stmtIt = block.getStatements().listIterator();
         while(stmtIt.hasNext()) {
            Statement statement = stmtIt.next();
            if(statement instanceof StatementConditionalJump) {
               StatementConditionalJump conditional = (StatementConditionalJump) statement;
               ConstantLiteral literal = findConditionLiteral(conditional);
               if(literal == null) {
                  literal = findConditionLiteralInterval(conditional);
               }
               if(literal == null) {
                  literal = findConditionLiteralDominance(block, conditional);
               }
               if(literal != null && literal instanceof ConstantBool) {
                  // Condition is a constant boolean
                  if(((ConstantBool) literal).getBool()) {
                     // Always true - replace default successor and drop conditional jump
                     Pass2EliminateUnusedBlocks.removePhiRValues(block.getLabel(), getGraph().getDefaultSuccessor(block), getLog());
                     block.setDefaultSuccessor(conditional.getDestination());
                     getLog().append("if() condition always true - replacing block destination " + conditional.toString(getProgram(), false));
                     stmtIt.remove();
                     block.setConditionalSuccessor(null);
                     modified = true;
                  } else {
                     // Always false - drop the conditional jump
                     Pass2EliminateUnusedBlocks.removePhiRValues(block.getLabel(), getGraph().getConditionalSuccessor(block), getLog());
                     getLog().append("if() condition always false - eliminating " + conditional.toString(getProgram(), false));
                     stmtIt.remove();
                     block.setConditionalSuccessor(null);
                     modified = true;
                  }
               }
            }
         }
      }
      return modified;
   }

   /**
    * If the condition always evaluates to constant true or false this finds tha value.
    *
    * @param conditional The conditional
    * @return The literal value of the condition
    */
   private ConstantLiteral findConditionLiteral(StatementConditionalJump conditional) {
      ConstantValue constValue1 = Pass2ConstantIdentification.getConstant(conditional.getrValue1());
      Operator operator = conditional.getOperator();
      ConstantValue constValue2 = Pass2ConstantIdentification.getConstant(conditional.getrValue2());
      try {

         // If all values are constant find the literal condition value
         if(conditional.getrValue1() == null && operator == null && constValue2 != null) {
            // Constant condition
            return constValue2.calculateLiteral(getProgramScope());
         } else if(conditional.getrValue1() == null && operator != null && constValue2 != null) {
            // Constant unary condition
            ConstantValue constVal = Pass2ConstantIdentification.createUnary((OperatorUnary) operator, constValue2);
            return constVal.calculateLiteral(getProgramScope());
         } else if(constValue1 != null && operator != null && constValue2 != null) {
            // Constant binary condition
            ConstantValue constVal = Pass2ConstantIdentification.createBinary(constValue1, (OperatorBinary) operator, constValue2, getProgramScope());
            return constVal.calculateLiteral(getProgramScope());
         }

      } catch(ConstantNotLiteral e) {
         // not literal - keep as null
      }
      return null;
   }

   /**
    * Examine the intervals of the conditions to see if they
    *
    * @param conditional The conditional
    * @return The literal value of the condition. Null if not literal.
    */
   private ConstantLiteral findConditionLiteralInterval(StatementConditionalJump conditional) {
      if(conditional.getrValue1() != null && conditional.getrValue2() != null) {
         IntValueInterval interval1 = getInterval(conditional.getrValue1());
         IntValueInterval interval2 = getInterval(conditional.getrValue2());
         if(interval1 != null && interval2 != null) {
            if(Operators.LT.equals(conditional.getOperator())) {
               if(interval1.maxValue < interval2.minValue) {
                  return new ConstantBool(true);
               } else if(interval1.minValue >= interval2.maxValue) {
                  return new ConstantBool(false);
               } else {
                  return null;
               }
            } else if(Operators.LE.equals(conditional.getOperator())) {
               if(interval1.maxValue <= interval2.minValue) {
                  return new ConstantBool(true);
               } else if(interval1.minValue > interval2.maxValue) {
                  return new ConstantBool(false);
               } else {
                  return null;
               }
            } else if(Operators.GT.equals(conditional.getOperator())) {
               if(interval1.minValue > interval2.maxValue) {
                  return new ConstantBool(true);
               } else if(interval1.maxValue <= interval2.minValue) {
                  return new ConstantBool(false);
               } else {
                  return null;
               }
            } else if(Operators.GE.equals(conditional.getOperator())) {
               if(interval1.minValue >= interval2.maxValue) {
                  return new ConstantBool(true);
               } else if(interval1.maxValue < interval2.minValue) {
                  return new ConstantBool(false);
               } else {
                  return null;
               }
         } else if(Operators.EQ.equals(conditional.getOperator())) {
            if(interval1.minValue > interval2.maxValue) {
               return new ConstantBool(false);
            } else if(interval1.maxValue < interval2.minValue) {
               return new ConstantBool(false);
            } else {
               return null;
            }
         } else if(Operators.NEQ.equals(conditional.getOperator())) {
            if(interval1.minValue > interval2.maxValue) {
               return new ConstantBool(true);
            } else if(interval1.maxValue < interval2.minValue) {
               return new ConstantBool(true);
            } else {
               return null;
            }
            }
         }
      }
      return null;
   }

   /** Interval of integer types. */
   static class IntValueInterval {
      long minValue;
      long maxValue;

      public IntValueInterval(long minValue, long maxValue) {
         this.minValue = minValue;
         this.maxValue = maxValue;
      }

   }

   private IntValueInterval getInterval(RValue rValue) {
      ConstantValue constValue = Pass2ConstantIdentification.getConstant(rValue);
      if(constValue != null) {
         try {
            ConstantLiteral constantLiteral = constValue.calculateLiteral(getProgramScope());
            if(constantLiteral instanceof ConstantInteger) {
               Long value = ((ConstantInteger) constantLiteral).getInteger();
               return new IntValueInterval(value, value);
            }
         } catch(ConstantNotLiteral e) {
            // not literal - keep as null
         }
      }
      // Not constant - find the interval of the type
      SymbolType symbolType = SymbolTypeInference.inferType(getProgramScope(), rValue);
      if(symbolType instanceof SymbolTypeIntegerFixed) {
         SymbolTypeIntegerFixed typeIntegerFixed = (SymbolTypeIntegerFixed) symbolType;
         return new IntValueInterval(typeIntegerFixed.getMinValue(), typeIntegerFixed.getMaxValue());
      } else if(symbolType.equals(SymbolType.UNUMBER)) {
         // Positive number
         return new IntValueInterval(0L, Long.MAX_VALUE);
      }
      // Not a constant integer
      return null;
   }

   private ConstantLiteral findConditionLiteralDominance(Graph.Block blockB, StatementConditionalJump conditionalB) {
      if(!isImmutableCondition(conditionalB)) return null;

      DominatorsGraph dominators = getProgram().getDominators();
      if(dominators == null) return null;
      DominatorsBlock bDoms = dominators.getDominators(blockB.getLabel());
      if(bDoms == null) return null;

      for(Graph.Block blockA : getGraph().getAllBlocks()) {
         if(blockA.equals(blockB)) continue;
         StatementConditionalJump conditionalA = getConditionalJump(blockA);
         if(conditionalA == null || !isImmutableCondition(conditionalA)) continue;

         LabelRef condSuccA = conditionalA.getDestination();
         LabelRef defaultSuccA = blockA.getDefaultSuccessor();

         // Check true branch of A
         if(condSuccA != null && bDoms.contains(condSuccA)) {
            Graph.Block condBlock = getGraph().getBlock(condSuccA);
            if(condBlock != null) {
               List<Graph.Block> preds = getGraph().getPredecessors(condBlock);
               if(preds.size() == 1 && preds.get(0).equals(blockA)) {
                  if(areConditionsIdentical(conditionalA, conditionalB)) {
                     getLog().append("RCE: Condition in " + blockB.getLabel() + " identical to " + blockA.getLabel() + " on true branch -> folding TRUE");
                     return new ConstantBool(true);
                  } else if(areConditionsInverse(conditionalA, conditionalB)) {
                     getLog().append("RCE: Condition in " + blockB.getLabel() + " inverse to " + blockA.getLabel() + " on true branch -> folding FALSE");
                     return new ConstantBool(false);
                  }
               }
            }
         }

         // Check false branch of A
         if(defaultSuccA != null && bDoms.contains(defaultSuccA)) {
            Graph.Block defaultBlock = getGraph().getBlock(defaultSuccA);
            if(defaultBlock != null) {
               List<Graph.Block> preds = getGraph().getPredecessors(defaultBlock);
               if(preds.size() == 1 && preds.get(0).equals(blockA)) {
                  if(areConditionsIdentical(conditionalA, conditionalB)) {
                     getLog().append("RCE: Condition in " + blockB.getLabel() + " identical to " + blockA.getLabel() + " on false branch -> folding FALSE");
                     return new ConstantBool(false);
                  } else if(areConditionsInverse(conditionalA, conditionalB)) {
                     getLog().append("RCE: Condition in " + blockB.getLabel() + " inverse to " + blockA.getLabel() + " on false branch -> folding TRUE");
                     return new ConstantBool(true);
                  }
               }
            }
         }
      }
      return null;
   }

   private StatementConditionalJump getConditionalJump(Graph.Block block) {
      for(Statement stmt : block.getStatements()) {
         if(stmt instanceof StatementConditionalJump) {
            return (StatementConditionalJump) stmt;
         }
      }
      return null;
   }

   private boolean isImmutableCondition(StatementConditionalJump conditional) {
      return isImmutableValue(conditional.getrValue1()) && isImmutableValue(conditional.getrValue2());
   }

   private boolean isImmutableValue(RValue val) {
      if(val == null) return true;
      if(val instanceof ConstantValue) return true;
      if(val instanceof VariableRef) {
         Variable variable = getProgramScope().getVariable((VariableRef) val);
         return variable != null && variable.getKind() != Variable.Kind.LOAD_STORE && variable.getKind() != Variable.Kind.PHI_MASTER;
      }
      return false;
   }

   private boolean areConditionsIdentical(StatementConditionalJump a, StatementConditionalJump b) {
      if(Objects.equals(a.getrValue1(), b.getrValue1()) &&
            Objects.equals(a.getOperator(), b.getOperator()) &&
            Objects.equals(a.getrValue2(), b.getrValue2())) {
         return true;
      }
      // Commutative EQ / NEQ
      if(Operators.EQ.equals(a.getOperator()) && Operators.EQ.equals(b.getOperator())) {
         return Objects.equals(a.getrValue1(), b.getrValue2()) && Objects.equals(a.getrValue2(), b.getrValue1());
      }
      if(Operators.NEQ.equals(a.getOperator()) && Operators.NEQ.equals(b.getOperator())) {
         return Objects.equals(a.getrValue1(), b.getrValue2()) && Objects.equals(a.getrValue2(), b.getrValue1());
      }
      // Flipped inequality: a < b is identical to b > a
      if(Operators.LT.equals(a.getOperator()) && Operators.GT.equals(b.getOperator())) {
         return Objects.equals(a.getrValue1(), b.getrValue2()) && Objects.equals(a.getrValue2(), b.getrValue1());
      }
      if(Operators.GT.equals(a.getOperator()) && Operators.LT.equals(b.getOperator())) {
         return Objects.equals(a.getrValue1(), b.getrValue2()) && Objects.equals(a.getrValue2(), b.getrValue1());
      }
      if(Operators.LE.equals(a.getOperator()) && Operators.GE.equals(b.getOperator())) {
         return Objects.equals(a.getrValue1(), b.getrValue2()) && Objects.equals(a.getrValue2(), b.getrValue1());
      }
      if(Operators.GE.equals(a.getOperator()) && Operators.LE.equals(b.getOperator())) {
         return Objects.equals(a.getrValue1(), b.getrValue2()) && Objects.equals(a.getrValue2(), b.getrValue1());
      }
      return false;
   }

   private boolean areConditionsInverse(StatementConditionalJump a, StatementConditionalJump b) {
      Operator opA = a.getOperator();
      Operator opB = b.getOperator();
      if(opA == null || opB == null) return false;

      // Same operands, inverse operator
      if(Objects.equals(a.getrValue1(), b.getrValue1()) && Objects.equals(a.getrValue2(), b.getrValue2())) {
         if(Operators.EQ.equals(opA) && Operators.NEQ.equals(opB)) return true;
         if(Operators.NEQ.equals(opA) && Operators.EQ.equals(opB)) return true;
         if(Operators.LT.equals(opA) && Operators.GE.equals(opB)) return true;
         if(Operators.GE.equals(opA) && Operators.LT.equals(opB)) return true;
         if(Operators.LE.equals(opA) && Operators.GT.equals(opB)) return true;
         if(Operators.GT.equals(opA) && Operators.LE.equals(opB)) return true;
      }
      // Swapped operands
      if(Objects.equals(a.getrValue1(), b.getrValue2()) && Objects.equals(a.getrValue2(), b.getrValue1())) {
         if(Operators.EQ.equals(opA) && Operators.NEQ.equals(opB)) return true;
         if(Operators.NEQ.equals(opA) && Operators.EQ.equals(opB)) return true;
         if(Operators.LT.equals(opA) && Operators.LE.equals(opB)) return true;
         if(Operators.LE.equals(opA) && Operators.LT.equals(opB)) return true;
         if(Operators.GT.equals(opA) && Operators.GE.equals(opB)) return true;
         if(Operators.GE.equals(opA) && Operators.GT.equals(opB)) return true;
      }
      return false;
   }

}
