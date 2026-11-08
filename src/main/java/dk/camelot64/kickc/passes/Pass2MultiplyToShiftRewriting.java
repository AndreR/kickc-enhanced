package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeInference;
import dk.camelot64.kickc.model.values.*;

import java.util.HashMap;
import java.util.ListIterator;
import java.util.Map;

/** Pass that replaces multiplications / division with factors of 2 with shifts */
public class Pass2MultiplyToShiftRewriting extends Pass2SsaOptimization {

   public static class Reciprocal {
      public final int m;
      public final int s;
      public Reciprocal(int m, int s) {
         this.m = m;
         this.s = s;
      }
   }

   private static final Map<Long, Reciprocal> RECIP_CACHE = new HashMap<>();

   public static Reciprocal findReciprocal(long d) {
      if(d <= 1 || d > 255) return null;
      if(RECIP_CACHE.containsKey(d)) return RECIP_CACHE.get(d);
      for(int s = 8; s <= 23; s++) {
         long m = (1L << s) / d + 1;
         if(255 * m > 65535) continue;
         boolean ok = true;
         for(int x = 0; x < 256; x++) {
            if(((x * m) >> s) != (x / d)) {
               ok = false;
               break;
            }
         }
         if(ok) {
            Reciprocal r = new Reciprocal((int) m, s);
            RECIP_CACHE.put(d, r);
            return r;
         }
      }
      RECIP_CACHE.put(d, null);
      return null;
   }

   public Pass2MultiplyToShiftRewriting(Program program) {
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

               if(Operators.MULTIPLY.equals(assignment.getOperator()) && assignment.getrValue1() instanceof ConstantValue) {
                  if(assignment.getrValue2() instanceof ConstantValue) continue;
                  final RValue varValue = assignment.getrValue2();
                  final Long constValue = getConstantInteger(assignment.getrValue1());
                  if(constValue == null)
                     continue;
                  optimized |= rewriteMultiply(assignment, stmtIt, constValue, varValue, scope);
               }

               if(Operators.MULTIPLY.equals(assignment.getOperator()) && assignment.getrValue2() instanceof ConstantValue) {
                  final RValue varValue = assignment.getrValue1();
                  final Long constValue = getConstantInteger(assignment.getrValue2());
                  if(constValue == null)
                     continue;
                  optimized |= rewriteMultiply(assignment, stmtIt, constValue, varValue, scope);
               }

               if(Operators.DIVIDE.equals(assignment.getOperator()) && assignment.getrValue2() instanceof ConstantValue) {
                  final RValue varValue = assignment.getrValue1();
                  final Long constValue = getConstantInteger(assignment.getrValue2());
                  if(constValue == null)
                     continue;
                  optimized |= rewriteDivide(assignment, stmtIt, constValue, varValue, scope);
               }

            }
         }
      }
      return optimized;
   }

   private boolean rewriteMultiply(StatementAssignment assignment, ListIterator<Statement> stmtIt, Long constValue, RValue varValue, Scope scope) {
      boolean optimized = false;
      double power2 = Math.log(constValue) / Math.log(2);
      if(power2 == 0.0) {
         // Found multiplication with 1 (ONE)
         getLog().append("Rewriting multiplication to remove identity multiply " + assignment.toString(getProgram(), false));
         assignment.setOperator(null);
         assignment.setrValue2(varValue);
         assignment.setrValue1(null);
         optimized = true;
      } else if(power2 > 0.0 && Math.round(power2) == power2) {
         // Found a whole power of 2
         getLog().append("Rewriting multiplication to use shift " + assignment.toString(getProgram(), false));
         SymbolType lValueType = SymbolTypeInference.inferType(getProgramScope(), assignment.getlValue());
         SymbolType varType = SymbolTypeInference.inferType(getProgramScope(), varValue);
         if(lValueType.getSizeBytes() > varType.getSizeBytes() || constValue > 255) {
            SymbolType targetType = lValueType.getSizeBytes() > varType.getSizeBytes() ? lValueType : SymbolType.WORD;
            stmtIt.previous();
            Variable varPromote = VariableBuilder.createIntermediate(scope, targetType, getProgram());
            stmtIt.add(new StatementAssignment((LValue) varPromote.getRef(), varValue, true, assignment.getSource(), Comment.NO_COMMENTS));
            stmtIt.next();
            varValue = varPromote.getRef();
         }
         assignment.setOperator(Operators.SHIFT_LEFT);
         assignment.setrValue2(new ConstantInteger((long) power2, SymbolType.BYTE));
         assignment.setrValue1(varValue);
         optimized = true;
      } else if(Operators.MULTIPLY.equals(assignment.getOperator())) {
         // Multiplication by constant
         getLog().append("Rewriting multiplication to use shift and addition" + assignment.toString(getProgram(), false));
         stmtIt.previous();
         SymbolType lValueType = SymbolTypeInference.inferType(getProgramScope(), assignment.getlValue());
         SymbolType varType = SymbolTypeInference.inferType(getProgramScope(), varValue);
         SymbolType resultType = varType;
         if(lValueType.getSizeBytes() > varType.getSizeBytes() || constValue > 255) {
            resultType = lValueType.getSizeBytes() > varType.getSizeBytes() ? lValueType : SymbolType.WORD;
         }
         RValue building = varValue;
         if(resultType.getSizeBytes() > varType.getSizeBytes()) {
            Variable varPromote = VariableBuilder.createIntermediate(scope, resultType, getProgram());
            stmtIt.add(new StatementAssignment((LValue) varPromote.getRef(), varValue, true, assignment.getSource(), Comment.NO_COMMENTS));
            varValue = varPromote.getRef();
            building = varPromote.getRef();
         }
         long pow2 = (long) power2;
         long remains = constValue - (1L << pow2);
         long shiftCount = 0;
         while(pow2 >= 0) {
            long powVal = 1L << pow2;
            if(remains >= powVal) {
               // First add shifts
               Variable varShift = VariableBuilder.createIntermediate(scope, resultType, getProgram());
               stmtIt.add(new StatementAssignment((LValue) varShift.getRef(), building, Operators.SHIFT_LEFT, new ConstantInteger(shiftCount, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
               shiftCount = 0;
               // Then add rvalue1
               Variable varAdd = VariableBuilder.createIntermediate(scope, resultType, getProgram());
               stmtIt.add(new StatementAssignment((LValue) varAdd.getRef(), varShift.getRef(), Operators.PLUS, varValue, true, assignment.getSource(), Comment.NO_COMMENTS));
               building = varAdd.getRef();
               remains -= powVal;
            }
            // Add a shift
            if(pow2 > 0) {
               shiftCount++;
            }
            pow2--;
         }
         // add remaining shifts
         if(shiftCount > 0) {
            Variable varShift = VariableBuilder.createIntermediate(scope, resultType, getProgram());
            stmtIt.add(new StatementAssignment((LValue) varShift.getRef(), building, Operators.SHIFT_LEFT, new ConstantInteger(shiftCount, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
            building = varShift.getRef();
         }
         stmtIt.next();
         // Replace old multiplication
         assignment.setOperator(null);
         assignment.setrValue1(null);
         assignment.setrValue2(building);
      }
      return optimized;
   }

   private boolean rewriteDivide(StatementAssignment assignment, ListIterator<Statement> stmtIt, Long constValue, RValue varValue, Scope scope) {
      boolean optimized = false;
      double power2 = Math.log(constValue) / Math.log(2);
      if(power2 == 0.0) {
         // Found division with 1 (ONE)
         getLog().append("Rewriting multiplication to remove identity divide " + assignment.toString(getProgram(), false));
         assignment.setOperator(null);
         assignment.setrValue2(varValue);
         assignment.setrValue1(null);
         optimized = true;
      } else if(power2 > 0.0 && Math.round(power2) == power2) {
         getLog().append("Rewriting division to use shift " + assignment.toString(getProgram(), false));
         assignment.setOperator(Operators.SHIFT_RIGHT);
         assignment.setrValue2(new ConstantInteger((long) power2, SymbolType.BYTE));
         optimized = true;
      } else {
         // Feature 13: Reciprocal Division for unsigned 8-bit integers by constant
         SymbolType varType = SymbolTypeInference.inferType(getProgramScope(), varValue);
         if(varType.getSizeBytes() == 1 && constValue > 1 && constValue <= 255) {
            Reciprocal recip = findReciprocal(constValue);
            if(recip != null) {
               getLog().append("Rewriting division by " + constValue + " to reciprocal multiplication (M=" + recip.m + ", S=" + recip.s + "): " + assignment.toString(getProgram(), false));
               stmtIt.previous();
               // varPromote = (word) varValue
               Variable varPromote = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
               stmtIt.add(new StatementAssignment((LValue) varPromote.getRef(), varValue, true, assignment.getSource(), Comment.NO_COMMENTS));
               // varMul = varPromote * M
               Variable varMul = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
               stmtIt.add(new StatementAssignment((LValue) varMul.getRef(), varPromote.getRef(), Operators.MULTIPLY, new ConstantInteger((long) recip.m, SymbolType.WORD), true, assignment.getSource(), Comment.NO_COMMENTS));

               RValue finalVal;
               if(recip.s >= 8) {
                  Variable varShift8 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
                  stmtIt.add(new StatementAssignment((LValue) varShift8.getRef(), varMul.getRef(), Operators.SHIFT_RIGHT, new ConstantInteger(8L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
                  if(recip.s > 8) {
                     Variable varCast = VariableBuilder.createIntermediate(scope, SymbolType.BYTE, getProgram());
                     stmtIt.add(new StatementAssignment((LValue) varCast.getRef(), varShift8.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));
                     Variable varShiftRem = VariableBuilder.createIntermediate(scope, SymbolType.BYTE, getProgram());
                     stmtIt.add(new StatementAssignment((LValue) varShiftRem.getRef(), varCast.getRef(), Operators.SHIFT_RIGHT, new ConstantInteger((long) (recip.s - 8), SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
                     finalVal = varShiftRem.getRef();
                  } else {
                     finalVal = varShift8.getRef();
                  }
               } else {
                  Variable varShift = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
                  stmtIt.add(new StatementAssignment((LValue) varShift.getRef(), varMul.getRef(), Operators.SHIFT_RIGHT, new ConstantInteger((long) recip.s, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
                  finalVal = varShift.getRef();
               }
               stmtIt.next();
               assignment.setOperator(null);
               assignment.setrValue1(null);
               assignment.setrValue2(finalVal);
               optimized = true;
            }
         }
      }
      return optimized;
   }

   /**
    * Get the constant integer value for an RValue - or null if not possible
    *
    * @param rValue The rValue
    * @return The constant literal integer value (or null)
    */
   private Long getConstantInteger(RValue rValue) {
      if(rValue instanceof ConstantValue) {
         ConstantValue constantValue = (ConstantValue) rValue;
         try {
            final ConstantLiteral constantLiteral = constantValue.calculateLiteral(getProgramScope());
            if(constantLiteral instanceof ConstantInteger)
               return ((ConstantInteger) constantLiteral).getInteger();
         } catch(ConstantNotLiteral e) {
            return null;
         }
      }
      return null;
   }


}
