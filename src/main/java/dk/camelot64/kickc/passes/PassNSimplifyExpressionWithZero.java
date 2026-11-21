package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.ConstantNotLiteral;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.iterator.ProgramExpressionBinary;
import dk.camelot64.kickc.model.iterator.ProgramExpressionIterator;
import dk.camelot64.kickc.model.operators.Operator;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.values.*;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simplify any binary expression containing a zero value (if possible
 */
public class PassNSimplifyExpressionWithZero extends Pass2SsaOptimization {

   public PassNSimplifyExpressionWithZero(Program program) {
      super(program);
   }

   @Override
   public boolean step() {
      AtomicBoolean modified = new AtomicBoolean(false);

      ProgramExpressionIterator.execute(getProgram(), (programExpression, currentStmt, stmtIt, currentBlock) -> {
         if(programExpression instanceof ProgramExpressionBinary) {
            ProgramExpressionBinary binary = (ProgramExpressionBinary) programExpression;
            RValue left = binary.getLeft();
            RValue right = binary.getRight();
            Operator operator = programExpression.getOperator();
            if(Operators.PLUS.equals(operator) || Operators.MINUS.equals(operator) || Operators.BOOL_OR.equals(operator) || Operators.BOOL_XOR.equals(operator)) {
               if(isZero(left)) {
                  if(Operators.MINUS.equals(operator)) {
                     // 0 - x == -x (never +x)
                     if(programExpression instanceof ProgramExpressionBinary.ProgramExpressionBinaryConstant && binary.getRight() instanceof ConstantValue) {
                        getLog().append("Simplifying 0 - constant to negation -" + binary.getRight().toString());
                        programExpression.set(new ConstantUnary(Operators.NEG, (ConstantValue) binary.getRight()));
                        modified.set(true);
                     } else if(currentStmt instanceof dk.camelot64.kickc.model.statements.StatementAssignment) {
                        dk.camelot64.kickc.model.statements.StatementAssignment assignment = (dk.camelot64.kickc.model.statements.StatementAssignment) currentStmt;
                        if(assignment.getrValue1() == left && assignment.getrValue2() == right && Operators.MINUS.equals(assignment.getOperator())) {
                           getLog().append("Simplifying 0 - x to unary negation in " + currentStmt.toString(getProgram(), false));
                           assignment.setrValue1(null);
                           assignment.setOperator(Operators.NEG);
                           modified.set(true);
                        }
                     }
                  } else {
                     getLog().append("Simplifying expression containing zero " + binary.getRight().toString() + " in " + (currentStmt == null ? "" : currentStmt.toString(getProgram(), false)));
                     if(programExpression instanceof ProgramExpressionBinary.ProgramExpressionBinaryPointerDereferenceIndexed) {
                        programExpression.set(new PointerDereferenceSimple(binary.getRight()));
                     } else {
                        programExpression.set(binary.getRight());
                     }
                     modified.set(true);
                  }
               } else if(isZero(right)) {
                  getLog().append("Simplifying expression containing zero " + binary.getLeft().toString() + " in " + (currentStmt == null ? "" : currentStmt.toString(getProgram(), false)));
                  if(programExpression instanceof ProgramExpressionBinary.ProgramExpressionBinaryPointerDereferenceIndexed) {
                     programExpression.set(new PointerDereferenceSimple(binary.getLeft()));
                  } else {
                     programExpression.set(binary.getLeft());
                  }
                  modified.set(true);
               }
            } else if(Operators.SHIFT_LEFT.equals(operator) || Operators.SHIFT_RIGHT.equals(operator)) {
               if(isZero(right)) {
                  // x << 0 -> x, x >> 0 -> x
                  getLog().append("Simplifying shift by zero " + binary.getLeft().toString() + " in " + (currentStmt == null ? "" : currentStmt.toString(getProgram(), false)));
                  programExpression.set(binary.getLeft());
                  modified.set(true);
               } else if(isZero(left)) {
                  // 0 << y -> 0, 0 >> y -> 0
                  getLog().append("Simplifying shift of zero in " + (currentStmt == null ? "" : currentStmt.toString(getProgram(), false)));
                  programExpression.set(binary.getLeft());
                  modified.set(true);
               } else {
                  Long shiftVal = getConstantIntegerValue(right);
                  if(shiftVal != null) {
                     dk.camelot64.kickc.model.types.SymbolType leftType = dk.camelot64.kickc.model.types.SymbolTypeInference.inferType(getProgramScope(), left);
                     if(leftType instanceof dk.camelot64.kickc.model.types.SymbolTypeIntegerFixed fixedLeft) {
                        int bitWidth = fixedLeft.getBits();
                        if(shiftVal < 0) {
                           getLog().append("Simplifying shift by negative count " + shiftVal + " to 0");
                           programExpression.set(new ConstantInteger(0L, fixedLeft));
                           modified.set(true);
                        } else if(shiftVal >= bitWidth) {
                           if(Operators.SHIFT_LEFT.equals(operator) || !fixedLeft.isSigned()) {
                              getLog().append("Simplifying shift >= bitwidth (" + shiftVal + " >= " + bitWidth + ") to 0");
                              programExpression.set(new ConstantInteger(0L, fixedLeft));
                              modified.set(true);
                           } else if(shiftVal > bitWidth - 1) {
                              // Signed right shift >= bitwidth fills all bits with sign bit: clamp to bitWidth - 1
                              getLog().append("Clamping signed right shift " + shiftVal + " to " + (bitWidth - 1));
                              binary.getRightValue().set(new ConstantInteger(bitWidth - 1L, dk.camelot64.kickc.model.types.SymbolType.BYTE));
                              modified.set(true);
                           }
                        }
                     }
                  }
               }
            }
         }
      });

      return modified.get();
   }

   public Long getConstantIntegerValue(RValue val) {
      if(val instanceof ConstantInteger ci) {
         return ci.getInteger();
      } else if(val instanceof ConstantValue cv) {
         try {
            ConstantLiteral literal = cv.calculateLiteral(getProgram().getScope());
            if(literal instanceof ConstantInteger ci)
               return ci.getInteger();
         } catch(ConstantNotLiteral e) {
            // Ignore
         }
      }
      return null;
   }

   /**
    * Determine if an RValue is effectively a zero
    *
    * @param left
    * @return
    */
   public boolean isZero(RValue left) {
      if(left instanceof ConstantInteger && ((ConstantInteger) left).getInteger() == 0) {
         return true;
      } else if(left instanceof ConstantValue) {
         try {
            ConstantLiteral literal = ((ConstantValue) left).calculateLiteral(getProgram().getScope());
            if(literal instanceof ConstantInteger && ((ConstantInteger) literal).getInteger() == 0L)
               return true;
         } catch(ConstantNotLiteral e) {
            // Ignore
         }
      }
      return false;
   }

}
