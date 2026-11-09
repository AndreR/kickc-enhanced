package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.Comment;
import dk.camelot64.kickc.model.ConstantNotLiteral;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.VariableBuilder;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeInference;
import dk.camelot64.kickc.model.types.SymbolTypeIntegerFixed;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.model.values.ConstantLiteral;
import dk.camelot64.kickc.model.values.ConstantValue;
import dk.camelot64.kickc.model.values.LValue;
import dk.camelot64.kickc.model.values.RValue;
import dk.camelot64.kickc.model.values.VariableRef;
import dk.camelot64.kickc.passes.utils.VarAssignments;

import java.util.List;
import java.util.ListIterator;

/** Pass that replaces modulo with factors of 2 with binary and, and modulo with constant using reciprocal division */
public class Pass2ModuloToAndRewriting extends Pass2SsaOptimization {

   public Pass2ModuloToAndRewriting(Program program) {
      super(program);
   }

   @Override
   public boolean step() {
      boolean optimized = false;

      for(var block : getGraph().getAllBlocks()) {
         Scope scope = getProgramScope().getScope(block.getScope());
         ListIterator<Statement> stmtIt = block.getStatements().listIterator();
         while(stmtIt.hasNext()) {
            Statement statement = stmtIt.next();
            if(statement instanceof StatementAssignment) {
               StatementAssignment assignment = (StatementAssignment) statement;
               if(Operators.MODULO.equals(assignment.getOperator()) && assignment.getrValue2() instanceof ConstantValue) {
                  final ConstantLiteral constantLiteral = getConstantLiteral((ConstantValue) assignment.getrValue2());
                  if(constantLiteral instanceof ConstantInteger) {
                     Long constantInt = ((ConstantInteger) constantLiteral).getInteger();
                     double power2 = Math.log(constantInt) / Math.log(2);
                     if(power2 >= 0.0 && Math.round(power2) == power2) {
                        // Modulo whole power of 2
                        assignment.setOperator(Operators.BOOL_AND);
                        assignment.setrValue2(new ConstantInteger(constantInt - 1L, SymbolType.BYTE));
                        getLog().append("Rewriting power 2 modulo to use AND " + assignment.toString(getProgram(), false));
                        optimized = true;
                     } else {
                        // Feature 13: Modulo non-power of 2: x % D = x - (x / D) * D
                        SymbolType varType = SymbolTypeInference.inferType(getProgramScope(), assignment.getrValue1());
                        if(varType.getSizeBytes() == 1 && constantInt > 1 && constantInt <= 255) {
                           Pass2MultiplyToShiftRewriting.Reciprocal recip = Pass2MultiplyToShiftRewriting.findReciprocal(constantInt);
                           if(recip != null) {
                              getLog().append("Rewriting modulo " + constantInt + " using reciprocal division: " + assignment.toString(getProgram(), false));
                              stmtIt.previous();
                              // q = x / D
                              Variable varQ = VariableBuilder.createIntermediate(scope, varType, getProgram());
                              stmtIt.add(new StatementAssignment((LValue) varQ.getRef(), assignment.getrValue1(), Operators.DIVIDE, new ConstantInteger(constantInt, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
                              // prod = q * D
                              Variable varProd = VariableBuilder.createIntermediate(scope, varType, getProgram());
                              stmtIt.add(new StatementAssignment((LValue) varProd.getRef(), varQ.getRef(), Operators.MULTIPLY, new ConstantInteger(constantInt, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
                              stmtIt.next();
                              // lValue = x - prod
                              assignment.setOperator(Operators.MINUS);
                              assignment.setrValue2(varProd.getRef());
                              optimized = true;
                           }
                        }
                     }
                  }
               } else if(Operators.MINUS.equals(assignment.getOperator()) && assignment.getrValue1() instanceof ConstantValue) {
                  final ConstantLiteral constantLiteral = getConstantLiteral((ConstantValue) assignment.getrValue1());
                  if(constantLiteral instanceof ConstantInteger constInt) {
                     long cVal = constInt.getValue();
                     if(isPowerOf2Minus1(cVal)) {
                        RValue xVal = assignment.getrValue2();
                        SymbolType xType = SymbolTypeInference.inferType(getProgramScope(), xVal);
                        boolean isSafe = false;
                        if(cVal == 255L && xType.getSizeBytes() == 1 && !(xType instanceof SymbolTypeIntegerFixed && ((SymbolTypeIntegerFixed) xType).isSigned())) {
                           isSafe = true;
                        } else if(cVal == 1L && SymbolType.BOOLEAN.equals(xType)) {
                           isSafe = true;
                        } else if(xVal instanceof VariableRef xVarRef && xType.getSizeBytes() == 1) {
                           isSafe = isVariableBoundedByConstant(xVarRef, cVal);
                        }
                        if(isSafe) {
                           assignment.setrValue1(xVal);
                           assignment.setOperator(Operators.BOOL_XOR);
                           assignment.setrValue2(new ConstantInteger(cVal, xType.getSizeBytes() == 1 ? SymbolType.BYTE : xType));
                           getLog().append("Strength reduction: rewrote " + cVal + " - x to x ^ " + cVal + ": " + assignment.toString(getProgram(), false));
                           optimized = true;
                        }
                     }
                  }
               }
            }
         }
      }
      return optimized;
   }

   private static boolean isPowerOf2Minus1(long val) {
      return val > 0 && ((val + 1) & val) == 0;
   }

   private boolean isVariableBoundedByConstant(VariableRef varRef, long cVal) {
      List<VarAssignments.VarAssignment> assigns = VarAssignments.get(varRef, getGraph(), getProgramScope());
      if(assigns == null || assigns.isEmpty()) return false;
      for(VarAssignments.VarAssignment va : assigns) {
         if(!VarAssignments.VarAssignment.Type.STATEMENT_LVALUE.equals(va.type)) {
            return false;
         }
         if(va.statementLValue instanceof StatementAssignment sa) {
            if(sa.getOperator() == null && sa.getrValue1() == null) {
               if(sa.getrValue2() instanceof ConstantValue cv) {
                  ConstantLiteral lit = getConstantLiteral(cv);
                  if(lit instanceof ConstantInteger ci) {
                     if(ci.getValue() < 0 || ci.getValue() > cVal) return false;
                  } else {
                     return false;
                  }
               } else {
                  return false;
               }
            } else if(Operators.MINUS.equals(sa.getOperator())) {
               if(sa.getrValue1() instanceof ConstantValue cv) {
                  ConstantLiteral lit = getConstantLiteral(cv);
                  if(lit instanceof ConstantInteger ci && ci.getValue() == cVal) {
                     if(varRef.equals(sa.getrValue2())) {
                        continue;
                     }
                  }
               }
               return false;
            } else if(Operators.BOOL_XOR.equals(sa.getOperator())) {
               if(sa.getrValue2() instanceof ConstantValue cv) {
                  ConstantLiteral lit = getConstantLiteral(cv);
                  if(lit instanceof ConstantInteger ci && ci.getValue() == cVal) {
                     if(varRef.equals(sa.getrValue1())) {
                        continue;
                     }
                  }
               }
               return false;
            } else {
               return false;
            }
         } else {
            return false;
         }
      }
      return true;
   }

   /**
    * Get the constant literal value for a constant value - or null if not possible
    *
    * @param constantValue The constant value
    * @return The constant literal value for RValue2 (or null)
    */
   private ConstantLiteral getConstantLiteral(ConstantValue constantValue) {
      try {
         return constantValue.calculateLiteral(getProgramScope());
      } catch(ConstantNotLiteral e) {
         return null;
      }
   }
}
