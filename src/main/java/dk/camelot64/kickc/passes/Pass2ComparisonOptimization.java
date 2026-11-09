package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.ConstantNotLiteral;
import dk.camelot64.kickc.model.ControlFlowBlock;
import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.operators.Operator;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementConditionalJump;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeInference;
import dk.camelot64.kickc.model.types.SymbolTypeIntegerFixed;
import dk.camelot64.kickc.model.values.ConstantBinary;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.model.values.ConstantLiteral;
import dk.camelot64.kickc.model.values.ConstantValue;

/**
 * The 6502/6510 handles some comparisons much faster than others.
 * This optimization rewrites to faster comparisons when possible.
 * Unsigned A > C is rewritten to A >= C+1 if the constant is <max.
 * Unsigned A <= C is rewritten to A < C+1 if the constant is <max.
 */
public class Pass2ComparisonOptimization extends Pass2SsaOptimization {

   public Pass2ComparisonOptimization(Program program) {
      super(program);
   }

   /**
    * Rewrite comparisons to faster ones if possible
    *
    * @return true optimization was performed. false if no optimization was possible.
    */
   @Override
   public boolean step() {
      for(var statement : getGraph().getAllStatements()) {
         if(statement instanceof StatementConditionalJump) {
            StatementConditionalJump conditionalJump = (StatementConditionalJump) statement;
            Operator operator = conditionalJump.getOperator();
            if(operator!=null && conditionalJump.getrValue2() instanceof ConstantValue) {
               SymbolType valueType = SymbolTypeInference.inferType(getProgramScope(), conditionalJump.getrValue1());
               ConstantValue constantValue = (ConstantValue) conditionalJump.getrValue2();
               ConstantLiteral constantLiteral = null;
               try {
                  constantLiteral = constantValue.calculateLiteral(getProgramScope());
               } catch(ConstantNotLiteral e) {
                  // Ignore
               }
               if(valueType instanceof SymbolTypeIntegerFixed && constantLiteral instanceof ConstantInteger) {
                  SymbolTypeIntegerFixed fixedType = (SymbolTypeIntegerFixed) valueType;
                  Long longValue = ((ConstantInteger) constantLiteral).getInteger();
                  long maxVal = fixedType.getMaxValue();
                  long minVal = fixedType.getMinValue();

                  // Rewrite signed comparison with -1: x > -1 -> x >= 0 (2 instructions)
                  if(fixedType.isSigned() && longValue == -1L && Operators.GT.equals(operator)) {
                     getLog().append("Rewriting signed x > -1 to x >= 0 " + statement.toString(getProgram(), false));
                     conditionalJump.setOperator(Operators.GE);
                     conditionalJump.setrValue2(new ConstantInteger(0L, fixedType));
                     return true;
                  }

                  // Rewrite signed comparison with -1: x <= -1 -> x < 0 (2 instructions)
                  if(fixedType.isSigned() && longValue == -1L && Operators.LE.equals(operator)) {
                     getLog().append("Rewriting signed x <= -1 to x < 0 " + statement.toString(getProgram(), false));
                     conditionalJump.setOperator(Operators.LT);
                     conditionalJump.setrValue2(new ConstantInteger(0L, fixedType));
                     return true;
                  }

                  if(Operators.GT.equals(operator)) {
                     // Found > C - rewrite to >= C+1 if possible (below max value)
                     if(longValue >= minVal && longValue < maxVal) {
                        getLog().append("Rewriting conditional comparison > to >= " + statement.toString(getProgram(), false));
                        conditionalJump.setOperator(Operators.GE);
                        conditionalJump.setrValue2(new ConstantInteger(longValue + 1L, fixedType));
                        return true;
                     }
                  }

                  if(Operators.LE.equals(operator)) {
                     // Found <= C - rewrite to < C+1 if possible (below max value)
                     if(longValue >= minVal && longValue < maxVal) {
                        getLog().append("Rewriting conditional comparison <= to < " + statement.toString(getProgram(), false));
                        conditionalJump.setOperator(Operators.LT);
                        conditionalJump.setrValue2(new ConstantInteger(longValue + 1L, fixedType));
                        return true;
                     }
                  }
               }
            }
         }
      }
      return false;
   }

}
