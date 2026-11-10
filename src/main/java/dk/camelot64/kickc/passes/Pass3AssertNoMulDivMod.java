package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.StatementAssignment;

/**
 * Assert that the code has no multiply/divide/modulo operators left.
 */
public class Pass3AssertNoMulDivMod extends Pass2SsaAssertion {

   public Pass3AssertNoMulDivMod(Program program) {
      super(program);
   }

   @Override
   public void check() throws AssertionFailed {
      if(getProgram().isEnableConvenience()) {
         // In convenience mode, all multiplications, divisions, and modulos
         // are either rewritten to library routines, deduplicated helper functions,
         // or validated by Pass 4 code generator ASM fragment matching.
         return;
      }
      for(var statement : getGraph().getAllStatements()) {
         if(statement instanceof StatementAssignment assignment) {
            if(Operators.MULTIPLY.equals(assignment.getOperator())) {
               throw new CompileError("Runtime multiplication not supported. " + statement.toString(getProgram(), false)
                  + "\nHint: For constants use power-of-two shifts/adds, or use fast_mul8u() from <multiply.h>.", statement.getSource());
            }
            if(Operators.DIVIDE.equals(assignment.getOperator())) {
               throw new CompileError("Runtime or unsupported division: " + statement.toString(getProgram(), false)
                  + "\nHint: For powers of 2 use '>>'. For runtime variables or constants use div8u()/div16u() from <division.h>, or enable '-fconvenience'.", statement.getSource());
            }
            if(Operators.MODULO.equals(assignment.getOperator())) {
               throw new CompileError("Runtime modulo not supported. " + statement.toString(getProgram(), false)
                  + "\nHint: For powers of 2 use '& (N-1)'. For runtime variables use rem8u()/rem16u() from <division.h>, or a POW10 subtraction loop for decimal formatting.", statement.getSource());
            }
         }
      }

   }
}
