package dk.camelot64.kickc.model.operators;

import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.symbols.ProgramScope;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.values.ConstantChar;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.model.values.ConstantLiteral;

/** Unary Cast to signed byte operator ( (signed byte) x ) */
public class OperatorCastSByte extends OperatorCast {

   public OperatorCastSByte(int precedence) {
      super("((signed byte))", "_sbyte_", precedence, SymbolType.SBYTE);
   }

   @Override
   public ConstantLiteral calculateLiteral(ConstantLiteral value, ProgramScope scope) {
      if(value instanceof ConstantInteger) {
         return new ConstantInteger((long) (byte) ((ConstantInteger) value).getValue().longValue(), SymbolType.SBYTE);
      } else if(value instanceof ConstantChar) {
         return new ConstantInteger((long) (byte) ((ConstantChar) value).getInteger().longValue(), SymbolType.SBYTE);
      } else if(value instanceof dk.camelot64.kickc.model.values.ConstantBool) {
         return new ConstantInteger(((dk.camelot64.kickc.model.values.ConstantBool) value).getBool() ? 1L : 0L, SymbolType.SBYTE);
      } else if(value instanceof dk.camelot64.kickc.model.values.ConstantPointer) {
         return new ConstantInteger((long) (byte) ((dk.camelot64.kickc.model.values.ConstantPointer) value).getLocation().longValue(), SymbolType.SBYTE);
      }
      throw new CompileError("Calculation not implemented " + getOperator() + " " + value );
   }

   @Override
   public SymbolType inferType(SymbolType operandType) {
      return SymbolType.SBYTE;
   }
}
