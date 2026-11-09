package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.iterator.ProgramExpressionIterator;
import dk.camelot64.kickc.model.iterator.ProgramExpressionUnary;
import dk.camelot64.kickc.model.operators.Operator;
import dk.camelot64.kickc.model.operators.OperatorCast;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.statements.StatementLValue;
import dk.camelot64.kickc.model.types.*;
import dk.camelot64.kickc.model.values.CastValue;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.model.values.ConstantPointer;
import dk.camelot64.kickc.model.values.RValue;
import dk.camelot64.kickc.model.values.VariableRef;
import dk.camelot64.kickc.passes.utils.VarAssignments;

import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simplifies casts
 * - Inlines casts of (number) constants
 * - Removes unnecessary casts
 * - Narrows binary arithmetic expressions when truncated to byte
 */
public class PassNCastSimplification extends Pass2SsaOptimization {

   public PassNCastSimplification(Program program) {
      super(program);
   }

   @Override
   public boolean step() {
      AtomicBoolean optimized = new AtomicBoolean(false);
      ProgramExpressionIterator.execute(getProgram(), (programExpression, currentStmt, stmtIt, currentBlock) -> {
         if(programExpression.getOperator() instanceof OperatorCast) {
            OperatorCast operatorCast = (OperatorCast) programExpression.getOperator();
            ProgramExpressionUnary unary = (ProgramExpressionUnary) programExpression;
            SymbolType castType = operatorCast.getToType();
            SymbolType operandType = SymbolTypeInference.inferType(getProgramScope(), ((ProgramExpressionUnary) programExpression).getOperand());
            RValue unaryOperand = unary.getOperand();
            if(!SymbolTypeConversion.assignmentCastNeeded(castType, operandType)) {
               // Cast Not needed
               programExpression.set(unaryOperand);
               getLog().append("Simplifying constant integer cast " + unaryOperand.toString(getProgram()));
               optimized.set(true);
            } else if(unaryOperand instanceof ConstantInteger) {
               ConstantInteger constantInteger = (ConstantInteger) unaryOperand;
               if(SymbolType.NUMBER.equals(constantInteger.getType())) {
                  if(castType instanceof SymbolTypeIntegerFixed || SymbolType.UNUMBER.equals(castType) || SymbolType.SNUMBER.equals(castType)) {
                     ConstantInteger newConstInt = new ConstantInteger(constantInteger.getInteger(), castType);
                     programExpression.set(newConstInt);
                     getLog().append("Simplifying constant integer cast " + newConstInt.toString());
                     optimized.set(true);
                  } else if(castType instanceof SymbolTypePointer) {
                     ConstantPointer newConstPointer = new ConstantPointer(constantInteger.getInteger(), ((SymbolTypePointer) castType).getElementType());
                     programExpression.set(newConstPointer);
                     getLog().append("Simplifying constant pointer cast " + newConstPointer.toString());
                     optimized.set(true);
                  }
               } else if(castType instanceof SymbolTypeIntegerFixed) {
                  if(((SymbolTypeIntegerFixed) castType).contains(constantInteger.getValue())) {
                     // Cast type contains the value - cast not needed
                     ConstantInteger newConstInt = new ConstantInteger(constantInteger.getInteger(), castType);
                     programExpression.set(newConstInt);
                     getLog().append("Simplifying constant integer cast " + newConstInt.toString());
                     optimized.set(true);
                  }
               }
            }
         }
      });

      // Narrow binary expressions whose result is cast to a 1-byte type
      for(var block : getGraph().getAllBlocks()) {
         ListIterator<Statement> stmtIt = block.getStatements().listIterator();
         while(stmtIt.hasNext()) {
            Statement statement = stmtIt.next();
            if(statement instanceof StatementAssignment assignment) {
               RValue rValue2 = assignment.getrValue2();
               SymbolType toType = null;
               RValue innerVal = null;
               if(assignment.getrValue1() == null && assignment.getOperator() instanceof OperatorCast) {
                  toType = ((OperatorCast) assignment.getOperator()).getToType();
                  innerVal = rValue2;
               } else if(assignment.getrValue1() == null && assignment.getOperator() == null && rValue2 instanceof CastValue) {
                  toType = ((CastValue) rValue2).getToType();
                  innerVal = ((CastValue) rValue2).getValue();
               }
               if(toType != null && toType.getSizeBytes() == 1 && innerVal instanceof VariableRef innerVarRef) {
                  SymbolType innerType = SymbolTypeInference.inferType(getProgramScope(), innerVarRef);
                  if(innerType.getSizeBytes() > 1) {
                     Collection<Integer> uses = getProgram().getVariableReferenceInfos().getVarUseStatements(innerVarRef);
                     if(uses != null && uses.size() <= 1) {
                        List<VarAssignments.VarAssignment> varAssignments = VarAssignments.get(innerVarRef, getGraph(), getProgramScope());
                        if(varAssignments.size() == 1 && VarAssignments.VarAssignment.Type.STATEMENT_LVALUE.equals(varAssignments.get(0).type)) {
                           StatementLValue defStmt = varAssignments.get(0).statementLValue;
                           if(defStmt instanceof StatementAssignment defAssignment) {
                              Operator op = defAssignment.getOperator();
                              if(op != null && isCommutativeMod256(op, defAssignment.getrValue2())) {
                                 RValue narrowedLeft = narrowToByte(defAssignment.getrValue1(), toType);
                                 RValue narrowedRight = narrowToByte(defAssignment.getrValue2(), toType);
                                 assignment.setrValue1(narrowedLeft);
                                 assignment.setOperator(op);
                                 assignment.setrValue2(narrowedRight);
                                 getLog().append("Narrowed binary expression to byte: " + assignment.toString(getProgram(), false));
                                 optimized.set(true);
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      return optimized.get();
   }

   private static boolean isCommutativeMod256(Operator op, RValue rVal2) {
      if(Operators.PLUS.equals(op) || Operators.MINUS.equals(op) ||
            Operators.BOOL_AND.equals(op) || Operators.BOOL_OR.equals(op) ||
            Operators.BOOL_XOR.equals(op) || Operators.MULTIPLY.equals(op)) {
         return true;
      }
      if(Operators.SHIFT_LEFT.equals(op) && rVal2 instanceof ConstantInteger) {
         return ((ConstantInteger) rVal2).getValue() >= 0 && ((ConstantInteger) rVal2).getValue() < 8;
      }
      return false;
   }

   private RValue narrowToByte(RValue val, SymbolType toType) {
      if(val instanceof ConstantInteger constInt) {
         return new ConstantInteger(constInt.getValue() & 0xFF, toType);
      }
      SymbolType valType = SymbolTypeInference.inferType(getProgramScope(), val);
      if(valType.getSizeBytes() == 1) {
         return val;
      }
      return new CastValue(toType, val);
   }

}
