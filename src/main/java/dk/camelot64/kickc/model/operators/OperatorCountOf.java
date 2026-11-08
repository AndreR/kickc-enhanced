package dk.camelot64.kickc.model.operators;

import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.symbols.ProgramScope;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypePointer;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.model.values.ConstantLiteral;
import dk.camelot64.kickc.model.values.ConstantValue;

/**
 * CountOf operator countof(expr).
 * Evaluates to the element count of an array at compile time.
 */
public class OperatorCountOf extends OperatorUnary {

   public OperatorCountOf(int precedence) {
      super("countof ", "_countof_", precedence);
   }

   @Override
   public ConstantLiteral calculateLiteral(ConstantLiteral operand, ProgramScope scope) {
      SymbolType type = operand.getType(scope);
      if(type instanceof SymbolTypePointer pointer && pointer.getArraySpec() != null) {
         ConstantValue arraySize = pointer.getArraySpec().getArraySize();
         if(arraySize instanceof ConstantInteger) {
            return (ConstantLiteral) arraySize;
         }
      }
      throw new CompileError("countof() argument must be an array type, found " + type.toCDecl());
   }

   @Override
   public SymbolType inferType(SymbolType operandType) {
      return SymbolType.WORD;
   }

}
