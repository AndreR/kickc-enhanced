package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.StaticAssert;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.values.ConstantEnumerable;
import dk.camelot64.kickc.model.values.ConstantLiteral;
import dk.camelot64.kickc.model.values.ConstantValue;
import dk.camelot64.kickc.model.values.RValue;
import dk.camelot64.kickc.model.values.SymbolVariableRef;
import java.util.Iterator;

/**
 * Checks static assertions during Pass 2 constant evaluation.
 * If an assertion is definitely false (0), immediately throws CompileError.
 * If definitely true (!= 0), removes it from the pending assertions list.
 */
public class Pass2AssertStaticAsserts extends Pass2SsaOptimization {

   public Pass2AssertStaticAsserts(Program program) {
      super(program);
   }

   @Override
   public boolean step() {
      Iterator<StaticAssert> it = getProgram().getStaticAsserts().iterator();
      while(it.hasNext()) {
         StaticAssert sa = it.next();
         RValue condition = sa.getCondition();
         if(condition instanceof SymbolVariableRef varRef) {
            Variable var = (Variable) getProgramScope().getSymbol(varRef);
            if(var != null && var.isKindConstant() && var.getInitValue() != null) {
               condition = var.getInitValue();
               sa.setCondition(condition);
            }
         }
         ConstantLiteral<?> literal = null;
         if(condition instanceof ConstantLiteral) {
            literal = (ConstantLiteral<?>) condition;
         } else if(condition instanceof ConstantValue) {
            try {
               literal = ((ConstantValue) condition).calculateLiteral(getProgramScope());
            } catch(Exception e) {
               // Ignore, might become constant later in Pass 2
            }
         }
         if(literal instanceof ConstantEnumerable<?> enumLit) {
            if(enumLit.getInteger() == 0) {
               String msg = sa.getMessage() != null ? "static assertion failed: \"" + sa.getMessage() + "\"" : "static assertion failed";
               throw new CompileError(msg, sa.getSource());
            } else {
               // Assertion passed! Remove.
               it.remove();
            }
         }
      }
      return false;
   }

}
