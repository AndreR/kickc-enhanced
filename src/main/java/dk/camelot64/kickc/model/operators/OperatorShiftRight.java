package dk.camelot64.kickc.model.operators;

import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.values.ConstantEnumerable;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.model.values.ConstantLiteral;

/** Binary shift right Operator ( x >> n ) */
public class OperatorShiftRight extends OperatorBinary {

   public OperatorShiftRight(int precedence) {
      super(">>", "_ror_", precedence, false);
   }

   @Override
   public ConstantLiteral calculateLiteral(ConstantLiteral left, ConstantLiteral right) {
      if(left instanceof ConstantEnumerable && right instanceof ConstantEnumerable) {
         long leftVal = ((ConstantEnumerable) left).getInteger();
         long shift = ((ConstantEnumerable) right).getInteger();
         if(shift < 0) {
            return new ConstantInteger(0L);
         }
         if(shift >= 64) {
            return new ConstantInteger(leftVal < 0 ? -1L : 0L);
         }
         return new ConstantInteger(leftVal >> shift);
      }
      throw new CompileError("Calculation not implemented " + left + " " + getOperator() + " " + right);
   }

   @Override
   public SymbolType inferType(SymbolType left, SymbolType right) {
         return left;
   }


}
