package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeInference;
import dk.camelot64.kickc.model.types.SymbolTypeIntegerFixed;
import dk.camelot64.kickc.model.values.*;

import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Pass that replaces multiplications / division with factors of 2 with shifts */
public class Pass2MultiplyToShiftRewriting extends Pass2SsaOptimization {

   public static class Reciprocal {
      public final long m;
      public final int s;
      public Reciprocal(long m, int s) {
         this.m = m;
         this.s = s;
      }
   }

   private static final Reciprocal NO_RECIPROCAL = new Reciprocal(0, 0);
   private static final Map<Long, Reciprocal> RECIP_CACHE = new ConcurrentHashMap<>();

   public static Reciprocal findReciprocal(long d) {
      if(d <= 1 || d > 255) return null;
      Reciprocal cached = RECIP_CACHE.get(d);
      if(cached != null) {
         return cached == NO_RECIPROCAL ? null : cached;
      }
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
            RECIP_CACHE.putIfAbsent(d, r);
            return r;
         }
      }
      RECIP_CACHE.putIfAbsent(d, NO_RECIPROCAL);
      return null;
   }

   public Pass2MultiplyToShiftRewriting(Program program) {
      super(program);
   }

   private static boolean isPowerOfTwo(long c) {
      return c > 0 && (c & (c - 1L)) == 0L;
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

   private RValue emitShiftAddChain(RValue baseVar, long multiplier, SymbolType resultType, StatementAssignment assignment, ListIterator<Statement> stmtIt, Scope scope) {
      RValue building = baseVar;
      int pow2 = 63 - Long.numberOfLeadingZeros(multiplier);
      long remains = multiplier - (1L << pow2);
      long shiftCount = 0;
      while(pow2 >= 0) {
         long powVal = 1L << pow2;
         if(remains >= powVal) {
            Variable varShift = VariableBuilder.createIntermediate(scope, resultType, getProgram());
            stmtIt.add(new StatementAssignment((LValue) varShift.getRef(), building, Operators.SHIFT_LEFT, new ConstantInteger(shiftCount, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
            shiftCount = 0;
            Variable varAdd = VariableBuilder.createIntermediate(scope, resultType, getProgram());
            stmtIt.add(new StatementAssignment((LValue) varAdd.getRef(), varShift.getRef(), Operators.PLUS, baseVar, true, assignment.getSource(), Comment.NO_COMMENTS));
            building = varAdd.getRef();
            remains -= powVal;
         }
         if(pow2 > 0) {
            shiftCount++;
         }
         pow2--;
      }
      if(shiftCount > 0) {
         Variable varShift = VariableBuilder.createIntermediate(scope, resultType, getProgram());
         stmtIt.add(new StatementAssignment((LValue) varShift.getRef(), building, Operators.SHIFT_LEFT, new ConstantInteger(shiftCount, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
         building = varShift.getRef();
      }
      return building;
   }

   private boolean rewriteMultiply(StatementAssignment assignment, ListIterator<Statement> stmtIt, Long constValue, RValue varValue, Scope scope) {
      if(constValue == 0L) {
         getLog().append("Rewriting multiplication by 0 to 0 " + assignment.toString(getProgram(), false));
         SymbolType lValueType = SymbolTypeInference.inferType(getProgramScope(), assignment.getlValue());
         assignment.setOperator(null);
         assignment.setrValue1(null);
         assignment.setrValue2(new ConstantInteger(0L, lValueType));
         return true;
      }
      if(constValue == 1L) {
         getLog().append("Rewriting multiplication to remove identity multiply " + assignment.toString(getProgram(), false));
         assignment.setOperator(null);
         assignment.setrValue2(varValue);
         assignment.setrValue1(null);
         return true;
      }
      if(constValue == -1L) {
         getLog().append("Rewriting multiplication by -1 to unary negation " + assignment.toString(getProgram(), false));
         assignment.setOperator(Operators.NEG);
         assignment.setrValue1(null);
         assignment.setrValue2(varValue);
         return true;
      }

      boolean negate = constValue < 0L;
      if(constValue == Long.MIN_VALUE) {
         return false;
      }
      long posConst = negate ? -constValue : constValue;

      if(isPowerOfTwo(posConst)) {
         long shiftAmount = Long.numberOfTrailingZeros(posConst);
         getLog().append("Rewriting multiplication to use shift " + assignment.toString(getProgram(), false));
         SymbolType lValueType = SymbolTypeInference.inferType(getProgramScope(), assignment.getlValue());
         SymbolType varType = SymbolTypeInference.inferType(getProgramScope(), varValue);
         if(lValueType.getSizeBytes() > varType.getSizeBytes() || (posConst > 255 && varType.getSizeBytes() < 2)) {
            SymbolType targetType = lValueType.getSizeBytes() > varType.getSizeBytes() ? lValueType : SymbolType.WORD;
            stmtIt.previous();
            Variable varPromote = VariableBuilder.createIntermediate(scope, targetType, getProgram());
            stmtIt.add(new StatementAssignment((LValue) varPromote.getRef(), varValue, true, assignment.getSource(), Comment.NO_COMMENTS));
            stmtIt.next();
            varValue = varPromote.getRef();
         }
         if(negate) {
            stmtIt.previous();
            Variable varShift = VariableBuilder.createIntermediate(scope, lValueType, getProgram());
            stmtIt.add(new StatementAssignment((LValue) varShift.getRef(), varValue, Operators.SHIFT_LEFT, new ConstantInteger(shiftAmount, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
            stmtIt.next();
            assignment.setOperator(Operators.NEG);
            assignment.setrValue1(null);
            assignment.setrValue2(varShift.getRef());
         } else {
            assignment.setOperator(Operators.SHIFT_LEFT);
            assignment.setrValue2(new ConstantInteger(shiftAmount, SymbolType.BYTE));
            assignment.setrValue1(varValue);
         }
         return true;
      }

      // Multiplication by non-power-of-2 constant: shift-add chain
      getLog().append("Rewriting multiplication to use shift and addition " + assignment.toString(getProgram(), false));
      stmtIt.previous();
      SymbolType lValueType = SymbolTypeInference.inferType(getProgramScope(), assignment.getlValue());
      SymbolType varType = SymbolTypeInference.inferType(getProgramScope(), varValue);
      SymbolType resultType = varType;
      if(lValueType.getSizeBytes() > varType.getSizeBytes() || (posConst > 255 && varType.getSizeBytes() < 2)) {
         resultType = lValueType.getSizeBytes() > varType.getSizeBytes() ? lValueType : SymbolType.WORD;
      }

      // Feature 16: Check if varValue was promoted from an 8-bit variable via a cast/assignment
      RValue actualVarValue = varValue;
      if(varType.getSizeBytes() > 1 && varValue instanceof VariableRef) {
         if(stmtIt.hasPrevious()) {
            Statement prevStmt = stmtIt.previous();
            if(prevStmt instanceof StatementAssignment prevAssign) {
               if(varValue.equals(prevAssign.getlValue()) && prevAssign.getOperator() == null) {
                  RValue prevRVal = prevAssign.getrValue2();
                  if(prevRVal instanceof CastValue castVal) {
                     SymbolType castFromType = SymbolTypeInference.inferType(getProgramScope(), castVal.getValue());
                     if(castFromType.getSizeBytes() == 1) {
                        actualVarValue = castVal.getValue();
                     }
                  } else {
                     SymbolType rType = SymbolTypeInference.inferType(getProgramScope(), prevRVal);
                     if(rType.getSizeBytes() == 1) {
                        actualVarValue = prevRVal;
                     }
                  }
               }
            }
            stmtIt.next();
         }
      }

      int trailingZeros = Long.numberOfTrailingZeros(posConst);
      long oddMultiplier = posConst >> trailingZeros;
      boolean canUseFactoredShift = resultType.getSizeBytes() == 2 &&
            trailingZeros > 0 &&
            oddMultiplier > 1 &&
            oddMultiplier <= 15;

      RValue building;
      if(canUseFactoredShift) {
         Variable varPromote = VariableBuilder.createIntermediate(scope, resultType, getProgram());
         stmtIt.add(new StatementAssignment((LValue) varPromote.getRef(), new CastValue(resultType, actualVarValue), true, assignment.getSource(), Comment.NO_COMMENTS));
         RValue building16 = emitShiftAddChain(varPromote.getRef(), oddMultiplier, resultType, assignment, stmtIt, scope);
         Variable varFinalShift = VariableBuilder.createIntermediate(scope, resultType, getProgram());
         stmtIt.add(new StatementAssignment((LValue) varFinalShift.getRef(), building16, Operators.SHIFT_LEFT, new ConstantInteger((long) trailingZeros, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
         building = varFinalShift.getRef();
      } else {
         RValue baseVar = varValue;
         if(resultType.getSizeBytes() > varType.getSizeBytes()) {
            Variable varPromote = VariableBuilder.createIntermediate(scope, resultType, getProgram());
            stmtIt.add(new StatementAssignment((LValue) varPromote.getRef(), varValue, true, assignment.getSource(), Comment.NO_COMMENTS));
            baseVar = varPromote.getRef();
         }
         building = emitShiftAddChain(baseVar, posConst, resultType, assignment, stmtIt, scope);
      }

      stmtIt.next();
      if(negate) {
         assignment.setOperator(Operators.NEG);
         assignment.setrValue1(null);
         assignment.setrValue2(building);
      } else {
         assignment.setOperator(null);
         assignment.setrValue1(null);
         assignment.setrValue2(building);
      }
      return true;
   }

   private boolean rewriteDivide(StatementAssignment assignment, ListIterator<Statement> stmtIt, Long constValue, RValue varValue, Scope scope) {
      if(constValue == 1L) {
         getLog().append("Rewriting division to remove identity divide " + assignment.toString(getProgram(), false));
         assignment.setOperator(null);
         assignment.setrValue2(varValue);
         assignment.setrValue1(null);
         return true;
      }

      SymbolType varType = SymbolTypeInference.inferType(getProgramScope(), varValue);
      boolean isSigned = varType instanceof SymbolTypeIntegerFixed && ((SymbolTypeIntegerFixed) varType).isSigned();
      if(isSigned) {
         // Shift-right and unsigned reciprocal division are only valid for unsigned types
         return false;
      }

      if(isPowerOfTwo(constValue)) {
         long shiftAmount = Long.numberOfTrailingZeros(constValue);
         getLog().append("Rewriting division to use shift " + assignment.toString(getProgram(), false));
         assignment.setOperator(Operators.SHIFT_RIGHT);
         assignment.setrValue2(new ConstantInteger(shiftAmount, SymbolType.BYTE));
         return true;
      }

      // Feature 13: Reciprocal Division for unsigned 8-bit integers by constant
      if(varType.getSizeBytes() == 1 && constValue > 1 && constValue <= 255) {
         Reciprocal recip = findReciprocal(constValue);
         if(recip != null) {
            getLog().append("Rewriting division by " + constValue + " to reciprocal multiplication (M=" + recip.m + ", S=" + recip.s + "): " + assignment.toString(getProgram(), false));
            stmtIt.previous();
            Variable varPromote = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
            stmtIt.add(new StatementAssignment((LValue) varPromote.getRef(), varValue, true, assignment.getSource(), Comment.NO_COMMENTS));
            Variable varMul = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
            stmtIt.add(new StatementAssignment((LValue) varMul.getRef(), varPromote.getRef(), Operators.MULTIPLY, new ConstantInteger((long) recip.m, SymbolType.WORD), true, assignment.getSource(), Comment.NO_COMMENTS));

            // findReciprocal always produces s in [8, 23]
            Variable varShift8 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
            stmtIt.add(new StatementAssignment((LValue) varShift8.getRef(), varMul.getRef(), Operators.SHIFT_RIGHT, new ConstantInteger(8L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
            RValue finalVal;
            if(recip.s > 8) {
               Variable varCast = VariableBuilder.createIntermediate(scope, SymbolType.BYTE, getProgram());
               stmtIt.add(new StatementAssignment((LValue) varCast.getRef(), varShift8.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));
               Variable varShiftRem = VariableBuilder.createIntermediate(scope, SymbolType.BYTE, getProgram());
               stmtIt.add(new StatementAssignment((LValue) varShiftRem.getRef(), varCast.getRef(), Operators.SHIFT_RIGHT, new ConstantInteger((long) (recip.s - 8), SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
               finalVal = varShiftRem.getRef();
            } else {
               finalVal = varShift8.getRef();
            }
            stmtIt.next();
            assignment.setOperator(null);
            assignment.setrValue1(null);
            assignment.setrValue2(finalVal);
            return true;
         }
      }
      return false;
   }

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
