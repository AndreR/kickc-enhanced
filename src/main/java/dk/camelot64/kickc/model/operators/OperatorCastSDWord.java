package dk.camelot64.kickc.model.operators;

import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.symbols.ProgramScope;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.values.ConstantChar;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.model.values.ConstantLiteral;

/** Unary Cast to signed double word operator ( (signed dword) x ) */
public class OperatorCastSDWord extends OperatorCast {

   public OperatorCastSDWord(int precedence) {
      super("((signed dword))", "_sdword_", precedence, SymbolType.SDWORD);
   }

   @Override
   public ConstantLiteral calculateLiteral(ConstantLiteral value, ProgramScope scope) {
      if(value instanceof ConstantInteger) {
         return new ConstantInteger((long) (int) ((ConstantInteger) value).getValue().longValue(), SymbolType.SDWORD);
      } else if(value instanceof ConstantChar) {
         return new ConstantInteger((long) (int) ((ConstantChar) value).getInteger().longValue(), SymbolType.SDWORD);
      } else if(value instanceof dk.camelot64.kickc.model.values.ConstantBool) {
         return new ConstantInteger(((dk.camelot64.kickc.model.values.ConstantBool) value).getBool() ? 1L : 0L, SymbolType.SDWORD);
      } else if(value instanceof dk.camelot64.kickc.model.values.ConstantPointer) {
         return new ConstantInteger((long) (int) ((dk.camelot64.kickc.model.values.ConstantPointer) value).getLocation().longValue(), SymbolType.SDWORD);
      }
      throw new CompileError("Calculation not implemented " + getOperator() + " " + value );
   }

   @Override
   public SymbolType inferType(SymbolType operandType) {
      return SymbolType.SDWORD;
   }
}
