package dk.camelot64.kickc.model.operators;

import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.ConstantNotLiteral;
import dk.camelot64.kickc.model.symbols.ProgramScope;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.values.*;

/** Unary Cast to tribyte (24-bit unsigned) operator ( (__uint24) x ) */
public class OperatorCastTribyte extends OperatorCast {

   public OperatorCastTribyte(int precedence) {
      super("((tribyte))", "_tribyte_", precedence, SymbolType.TRIBYTE);
   }

   @Override
   public ConstantLiteral calculateLiteral(ConstantLiteral value, ProgramScope scope) {
      if(value instanceof ConstantInteger) {
         return new ConstantInteger(0xffffffL & ((ConstantInteger) value).getValue(), SymbolType.TRIBYTE);
      } else if(value instanceof ConstantChar) {
         return new ConstantInteger(((ConstantChar) value).getInteger(), SymbolType.TRIBYTE);
      } else if(value instanceof ConstantPointer) {
         return new ConstantInteger(0xffff & ((ConstantPointer) value).getLocation(), SymbolType.TRIBYTE);
      } else if(value instanceof ConstantString) {
         throw ConstantNotLiteral.getException();
      }
      throw new CompileError("Calculation not implemented " + getOperator() + " " + value );
   }

   @Override
   public SymbolType inferType(SymbolType operandType) {
      return SymbolType.TRIBYTE;
   }
}
