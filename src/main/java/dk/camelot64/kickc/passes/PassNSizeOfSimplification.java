package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.ConstantNotLiteral;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.iterator.ProgramValue;
import dk.camelot64.kickc.model.iterator.ProgramValueIterator;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypePointer;
import dk.camelot64.kickc.model.values.ConstantArrayList;
import dk.camelot64.kickc.model.values.ConstantBinary;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.model.values.ConstantLiteral;
import dk.camelot64.kickc.model.values.ConstantRef;
import dk.camelot64.kickc.model.values.ConstantString;
import dk.camelot64.kickc.model.values.ConstantUnary;
import dk.camelot64.kickc.model.values.ConstantValue;
import dk.camelot64.kickc.model.values.SymbolVariableRef;
import dk.camelot64.kickc.passes.utils.SizeOfConstants;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simplifies sizeof() operators whenever the expression is constant
 */
public class PassNSizeOfSimplification extends Pass2SsaOptimization {

   public PassNSizeOfSimplification(Program program) {
      super(program);
   }

   @Override
   public boolean step() {
      AtomicBoolean modified = new AtomicBoolean(false);

      for(var statement : getGraph().getAllStatements()) {
         if(statement instanceof StatementAssignment assignment) {
            if(Operators.SIZEOF.equals(assignment.getOperator())) {
               if(assignment.getrValue2() instanceof SymbolVariableRef symbolRef) {
                  Variable symbolVar = (Variable) getProgramScope().getSymbol(symbolRef);
                  SymbolType symbolType = symbolVar.getType();
                  if(!(symbolVar.isArray())) {
                     getLog().append("Resolving sizeof() " + assignment.toString(getProgram(), false));
                     ConstantRef sizeOfConstantVar = SizeOfConstants.getSizeOfConstantVar(getProgramScope(), symbolType);
                     assignment.setrValue2(sizeOfConstantVar);
                     assignment.setOperator(null);
                     modified.set(true);
                  } else if(symbolType instanceof SymbolTypePointer arrayType && SymbolType.NIBBLE.equals(arrayType.getElementType())) {
                     ConstantValue arraySize = symbolVar.getArraySize();
                     if(arraySize != null) {
                        getLog().append("Resolving packed nibble array sizeof() " + assignment.toString(getProgram(), false));
                        ConstantBinary add1 = new ConstantBinary(arraySize, Operators.PLUS, new ConstantInteger(1L));
                        assignment.setrValue2(new ConstantBinary(add1, Operators.SHIFT_RIGHT, new ConstantInteger(1L)));
                        assignment.setOperator(null);
                        modified.set(true);
                     } else if(symbolVar.getInitValue() instanceof ConstantArrayList list) {
                        getLog().append("Resolving packed nibble array sizeof() " + assignment.toString(getProgram(), false));
                        int size = list.getElements().size();
                        int packedSize = (size + 1) / 2;
                        assignment.setrValue2(new ConstantInteger((long) packedSize, SymbolType.WORD));
                        assignment.setOperator(null);
                        modified.set(true);
                     }
                  }
               }
            } else if(Operators.COUNTOF.equals(assignment.getOperator())) {
               if(assignment.getrValue2() instanceof SymbolVariableRef symbolRef) {
                  Variable symbolVar = (Variable) getProgramScope().getSymbol(symbolRef);
                  if(!symbolVar.isArray()) {
                     throw new CompileError("countof() argument must be an array with known static size, found: " + symbolVar.getType().toCDecl(), assignment.getSource());
                  }
                  if(symbolVar.getArraySize() != null) {
                     getLog().append("Resolving countof() " + assignment.toString(getProgram(), false));
                     assignment.setrValue2(symbolVar.getArraySize());
                     assignment.setOperator(null);
                     modified.set(true);
                  } else if(symbolVar.getInitValue() instanceof ConstantArrayList list) {
                     getLog().append("Resolving countof() " + assignment.toString(getProgram(), false));
                     assignment.setrValue2(new ConstantInteger((long) list.getElements().size(), SymbolType.WORD));
                     assignment.setOperator(null);
                     modified.set(true);
                  }
               }
            }
         }
      }

      ProgramValueIterator.execute(getProgram(), (programValue, currentStmt, stmtIt, currentBlock) -> {
               if(programValue.get() instanceof ConstantUnary unary) {
                  if(unary.getOperator().equals(Operators.SIZEOF)) {
                     ConstantValue operand = unary.getOperand();
                     resolveConstantSizeOf(modified, programValue, unary, operand);
                  } else if(unary.getOperator().equals(Operators.COUNTOF)) {
                     ConstantValue operand = unary.getOperand();
                     resolveConstantCountOf(modified, programValue, unary, operand);
                  }
               }
            }
      );

      return modified.get();
   }

   public void resolveConstantSizeOf(AtomicBoolean modified, ProgramValue programValue, ConstantUnary unary, ConstantValue operand) {
      if(operand instanceof ConstantRef) {
         Variable constant = getProgramScope().getConstant((ConstantRef) operand);
         SymbolType symbolType = constant.getType();
         if(constant.isArray() && symbolType instanceof SymbolTypePointer arrayType) {
            ConstantValue arraySize = constant.getArraySize();
            if(arraySize!=null) {
               getLog().append("Resolving array sizeof() " + unary.toString(getProgram()));
               if(SymbolType.NIBBLE.equals(arrayType.getElementType())) {
                  ConstantBinary add1 = new ConstantBinary(arraySize, Operators.PLUS, new ConstantInteger(1L));
                  programValue.set(new ConstantBinary(add1, Operators.SHIFT_RIGHT, new ConstantInteger(1L)));
               } else {
                  ConstantRef sizeOfConstantVar = SizeOfConstants.getSizeOfConstantVar(getProgramScope(), arrayType.getElementType());
                  programValue.set(new ConstantBinary(arraySize, Operators.MULTIPLY, sizeOfConstantVar));
               }
               modified.set(true);
            } else if(constant.getInitValue() instanceof ConstantArrayList list) {
               getLog().append("Resolving array sizeof() " + unary.toString(getProgram()));
               int size = list.getElements().size();
               if(SymbolType.NIBBLE.equals(arrayType.getElementType())) {
                  int packedSize = (size + 1) / 2;
                  programValue.set(new ConstantInteger((long) packedSize));
               } else {
                  ConstantRef sizeOfConstantVar = SizeOfConstants.getSizeOfConstantVar(getProgramScope(), arrayType.getElementType());
                  programValue.set(new ConstantBinary(new ConstantInteger((long) size), Operators.MULTIPLY, sizeOfConstantVar));
               }
               modified.set(true);
            } else {
               // Try to calculate the literal to check if it is a string
               ConstantLiteral<?> stringLiteral = null;
               try {
                  stringLiteral = constant.getInitValue().calculateLiteral(getProgram().getScope());
               } catch(ConstantNotLiteral e) {
                  // Ignore
               }
               if(stringLiteral instanceof ConstantString constString) {
                  int length = constString.getStringLength();
                  getLog().append("Resolving string sizeof() " + unary.toString(getProgram()));
                  ConstantRef sizeOfChar = SizeOfConstants.getSizeOfConstantVar(getProgramScope(), SymbolType.BYTE);
                  programValue.set(new ConstantBinary(new ConstantInteger((long) length), Operators.MULTIPLY, sizeOfChar));
                  modified.set(true);
               } else {
                  throw new CompileError("Not implemented!");
               }
            }
         } else if(symbolType instanceof SymbolTypePointer ){
            getLog().append("Resolving sizeof() " + unary.toString(getProgram()));
            ConstantRef sizeOfConstantVar = SizeOfConstants.getSizeOfConstantVar(getProgramScope(), symbolType);
            programValue.set(sizeOfConstantVar);
            modified.set(true);
         } else {
            getLog().append("Resolving sizeof() " + unary.toString(getProgram()));
            ConstantLiteral<?> literal = operand.calculateLiteral(getProgram().getScope());
            SymbolType constType = literal.getType(getProgram().getScope());
            ConstantRef sizeOfConstantVar = SizeOfConstants.getSizeOfConstantVar(getProgramScope(), constType);
            programValue.set(sizeOfConstantVar);
            modified.set(true);
         }
      } else {
         getLog().append("Resolving sizeof() " + unary.toString(getProgram()));
         ConstantLiteral<?> literal = operand.calculateLiteral(getProgram().getScope());
         SymbolType constType = literal.getType(getProgram().getScope());
         ConstantRef sizeOfConstantVar = SizeOfConstants.getSizeOfConstantVar(getProgramScope(), constType);
         programValue.set(sizeOfConstantVar);
         modified.set(true);
      }
   }


   public void resolveConstantCountOf(AtomicBoolean modified, ProgramValue programValue, ConstantUnary unary, ConstantValue operand) {
      if(operand instanceof ConstantRef) {
         Variable constant = getProgramScope().getConstant((ConstantRef) operand);
         if(constant.isArray()) {
            ConstantValue arraySize = constant.getArraySize();
            if(arraySize != null) {
               getLog().append("Resolving array countof() " + unary.toString(getProgram()));
               programValue.set(arraySize);
               modified.set(true);
            } else if(constant.getInitValue() instanceof ConstantArrayList list) {
               getLog().append("Resolving array countof() " + unary.toString(getProgram()));
               programValue.set(new ConstantInteger((long) list.getElements().size(), SymbolType.WORD));
               modified.set(true);
            } else {
               ConstantLiteral<?> stringLiteral = null;
               try {
                  stringLiteral = constant.getInitValue().calculateLiteral(getProgram().getScope());
               } catch(ConstantNotLiteral e) {
                  // Ignore
               }
               if(stringLiteral instanceof ConstantString constString) {
                  getLog().append("Resolving string countof() " + unary.toString(getProgram()));
                  programValue.set(new ConstantInteger((long) constString.getStringLength(), SymbolType.WORD));
                  modified.set(true);
               } else {
                  throw new CompileError("countof() requires an array with known static size");
               }
            }
         } else {
            throw new CompileError("countof() argument must be an array, found: " + constant.getType().toCDecl());
         }
      }
   }
}
