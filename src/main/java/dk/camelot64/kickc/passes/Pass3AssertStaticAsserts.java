package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.StaticAssert;

/**
 * Asserts that all static assertions have been evaluated to compile-time constants.
 * If any static assertion remains unresolved, throws a CompileError.
 */
public class Pass3AssertStaticAsserts extends Pass2SsaAssertion {

   public Pass3AssertStaticAsserts(Program program) {
      super(program);
   }

   @Override
   public void check() throws AssertionFailed {
      if(!getProgram().getStaticAsserts().isEmpty()) {
         StaticAssert sa = getProgram().getStaticAsserts().get(0);
         throw new CompileError("static assertion condition is not a compile-time constant: " + sa.getCondition().toString(getProgram()), sa.getSource());
      }
   }

}
