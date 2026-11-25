package dk.camelot64.kickc.model.operators;

import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.symbols.ProgramScope;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.values.ConstantChar;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.model.values.ConstantLiteral;

/** Unary Cast to signed word operator ( (signed word) x ) */
public class OperatorCastSWord extends OperatorCast {

   public OperatorCastSWord(int precedence) {
      super("((signed word))", "_sword_", precedence, SymbolType.SWORD);
   }

   @Override
   public ConstantLiteral calculateLiteral(ConstantLiteral value, ProgramScope scope) {
      if(value instanceof ConstantInteger) {
         return new ConstantInteger((long) (short) ((ConstantInteger) value).getValue().longValue(), SymbolType.SWORD);
      } else if(value instanceof ConstantChar) {
         return new ConstantInteger((long) (short) ((ConstantChar) value).getInteger().longValue(), SymbolType.SWORD);
      } else if(value instanceof dk.camelot64.kickc.model.values.ConstantBool) {
         return new ConstantInteger(((dk.camelot64.kickc.model.values.ConstantBool) value).getBool() ? 1L : 0L, SymbolType.SWORD);
      } else if(value instanceof dk.camelot64.kickc.model.values.ConstantPointer) {
         return new ConstantInteger((long) (short) ((dk.camelot64.kickc.model.values.ConstantPointer) value).getLocation().longValue(), SymbolType.SWORD);
      }
      throw new CompileError("Calculation not implemented " + getOperator() + " " + value );
   }

   @Override
   public SymbolType inferType(SymbolType operandType) {
      return SymbolType.SWORD;
   }
}
