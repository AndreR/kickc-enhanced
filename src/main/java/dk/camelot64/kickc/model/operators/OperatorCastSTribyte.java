package dk.camelot64.kickc.model.operators;

import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.ConstantNotLiteral;
import dk.camelot64.kickc.model.symbols.ProgramScope;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.values.*;

/** Unary Cast to signed tribyte (24-bit signed) operator ( (__int24) x ) */
public class OperatorCastSTribyte extends OperatorCast {

   public OperatorCastSTribyte(int precedence) {
      super("((signed tribyte))", "_stribyte_", precedence, SymbolType.STRIBYTE);
   }

   @Override
   public ConstantLiteral calculateLiteral(ConstantLiteral value, ProgramScope scope) {
      if(value instanceof ConstantInteger) {
         long val = ((ConstantInteger) value).getValue();
         long signExt = ((val & 0x800000L) != 0) ? (val | ~0xffffffL) : (val & 0xffffffL);
         return new ConstantInteger(signExt, SymbolType.STRIBYTE);
      } else if(value instanceof ConstantChar) {
         return new ConstantInteger(((ConstantChar) value).getInteger(), SymbolType.STRIBYTE);
      } else if(value instanceof ConstantPointer) {
         return new ConstantInteger(0xffff & ((ConstantPointer) value).getLocation(), SymbolType.STRIBYTE);
      } else if(value instanceof ConstantString) {
         throw ConstantNotLiteral.getException();
      }
      throw new CompileError("Calculation not implemented " + getOperator() + " " + value );
   }

   @Override
   public SymbolType inferType(SymbolType operandType) {
      return SymbolType.STRIBYTE;
   }
}
