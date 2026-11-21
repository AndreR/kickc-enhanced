package dk.camelot64.kickc.model.operators;

import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.symbols.ProgramScope;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.values.ConstantEnumerable;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.model.values.ConstantLiteral;

/** Unary bitwise Not operator (~b) */
public class OperatorBitwiseNot extends OperatorUnary {

   public OperatorBitwiseNot(int precedence) {
      super("~", "_bnot_", precedence);
   }

   @Override
   public ConstantLiteral calculateLiteral(ConstantLiteral left, ProgramScope scope) {
      if(left instanceof ConstantEnumerable) {
         long val = ~((ConstantEnumerable) left).getInteger();
         SymbolType leftType = left.getType(scope);
         if(leftType instanceof dk.camelot64.kickc.model.types.SymbolTypeIntegerFixed) {
            dk.camelot64.kickc.model.types.SymbolTypeIntegerFixed fixedType = (dk.camelot64.kickc.model.types.SymbolTypeIntegerFixed) leftType;
            return new ConstantInteger(fixedType.normalize(val), fixedType);
         }
         return new ConstantInteger(val);
      }
      throw new CompileError("Calculation not implemented " + getOperator() + " " + left );
   }

   @Override
   public SymbolType inferType(SymbolType operandType) {
      return operandType;
   }

}
