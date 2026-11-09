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

   public static final Map<Long, Integer> inlinedDivCounts = new java.util.LinkedHashMap<>();


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
         if(lValueType.getSizeBytes() > varType.getSizeBytes() || (constValue > 255 && varType.getSizeBytes() < 2)) {
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
         if(lValueType.getSizeBytes() > varType.getSizeBytes() || (constValue > 255 && varType.getSizeBytes() < 2)) {
            resultType = lValueType.getSizeBytes() > varType.getSizeBytes() ? lValueType : SymbolType.WORD;
         }

         // Feature 16: Check if varValue was promoted from an 8-bit variable via a cast/assignment
         RValue actualVarValue = varValue;
         SymbolType actualVarType = varType;
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
                           actualVarType = castFromType;
                        }
                     } else {
                        SymbolType rType = SymbolTypeInference.inferType(getProgramScope(), prevRVal);
                        if(rType.getSizeBytes() == 1) {
                           actualVarValue = prevRVal;
                           actualVarType = rType;
                        }
                     }
                  }
               }
               stmtIt.next(); // Restore iterator to current assignment position
            }
         }

         // Check if we can factor out trailing zeros for compact shift-add multiplication
         int trailingZeros = Long.numberOfTrailingZeros(constValue);
         long oddMultiplier = constValue >> trailingZeros;
         boolean canUseFactoredShift = resultType.getSizeBytes() == 2 &&
               trailingZeros > 0 &&
               oddMultiplier > 1 &&
               oddMultiplier <= 15;

         if(canUseFactoredShift) {
            // Step 1: Promote actualVarValue to 16-bit resultType
            Variable varPromote = VariableBuilder.createIntermediate(scope, resultType, getProgram());
            stmtIt.add(new StatementAssignment((LValue) varPromote.getRef(), new CastValue(resultType, actualVarValue), true, assignment.getSource(), Comment.NO_COMMENTS));

            // Step 2: Accumulate (varPromote * oddMultiplier) in 16-bit
            RValue building16 = varPromote.getRef();
            double oddPower2 = Math.log(oddMultiplier) / Math.log(2);
            long pow2 = (long) oddPower2;
            long remains = oddMultiplier - (1L << pow2);
            long shiftCount = 0;
            while(pow2 >= 0) {
               long powVal = 1L << pow2;
               if(remains >= powVal) {
                  Variable varShift = VariableBuilder.createIntermediate(scope, resultType, getProgram());
                  stmtIt.add(new StatementAssignment((LValue) varShift.getRef(), building16, Operators.SHIFT_LEFT, new ConstantInteger(shiftCount, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
                  shiftCount = 0;
                  Variable varAdd = VariableBuilder.createIntermediate(scope, resultType, getProgram());
                  stmtIt.add(new StatementAssignment((LValue) varAdd.getRef(), varShift.getRef(), Operators.PLUS, varPromote.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));
                  building16 = varAdd.getRef();
                  remains -= powVal;
               }
               if(pow2 > 0) {
                  shiftCount++;
               }
               pow2--;
            }
            if(shiftCount > 0) {
               Variable varShift = VariableBuilder.createIntermediate(scope, resultType, getProgram());
               stmtIt.add(new StatementAssignment((LValue) varShift.getRef(), building16, Operators.SHIFT_LEFT, new ConstantInteger(shiftCount, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
               building16 = varShift.getRef();
            }

            // Step 3: Shift the accumulated result by the trailing zeros
            Variable varFinalShift = VariableBuilder.createIntermediate(scope, resultType, getProgram());
            stmtIt.add(new StatementAssignment((LValue) varFinalShift.getRef(), building16, Operators.SHIFT_LEFT, new ConstantInteger((long) trailingZeros, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));

            stmtIt.next();
            assignment.setOperator(null);
            assignment.setrValue1(null);
            assignment.setrValue2(varFinalShift.getRef());
            optimized = true;
            return optimized;
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

   private RValue emitDivConstWordRecursive(StatementAssignment assignment, ListIterator<Statement> stmtIt, RValue currentVal, Scope scope, long divisor) {
      if(divisor == 1) {
         return currentVal;
      }
      // Powers of 2: shift right
      if((divisor & (divisor - 1)) == 0) {
         int shift = Long.numberOfTrailingZeros(divisor);
         Variable res = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
         stmtIt.add(new StatementAssignment((LValue) res.getRef(), currentVal, Operators.SHIFT_RIGHT, new ConstantInteger((long) shift, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
         return res.getRef();
      }
      // If divisible by 2: extract power of 2
      int trailingZeros = Long.numberOfTrailingZeros(divisor);
      if(trailingZeros > 0) {
         long rem = divisor >> trailingZeros;
         RValue q = emitDivConstWordRecursive(assignment, stmtIt, currentVal, scope, rem);
         if(q == null) return null;
         Variable res = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
         stmtIt.add(new StatementAssignment((LValue) res.getRef(), q, Operators.SHIFT_RIGHT, new ConstantInteger((long) trailingZeros, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
         return res.getRef();
      }
      // Divisible by 5 (and note 10 = 2 * 5 is handled above by 5 then shift >> 1)
      if(divisor % 5 == 0) {
         RValue q5 = emitDiv5Word(assignment, stmtIt, currentVal, scope);
         return emitDivConstWordRecursive(assignment, stmtIt, q5, scope, divisor / 5);
      }
      // Divisible by 3
      if(divisor % 3 == 0) {
         RValue q3 = emitDiv3Word(assignment, stmtIt, currentVal, scope);
         return emitDivConstWordRecursive(assignment, stmtIt, q3, scope, divisor / 3);
      }
      // Divisible by 7
      if(divisor % 7 == 0) {
         RValue q7 = emitDiv7Word(assignment, stmtIt, currentVal, scope);
         return emitDivConstWordRecursive(assignment, stmtIt, q7, scope, divisor / 7);
      }
      return null;
   }

   private RValue emitDiv5Word(StatementAssignment assignment, ListIterator<Statement> stmtIt, RValue varValue, Scope scope) {
      Variable q1 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q1.getRef(), varValue, Operators.SHIFT_RIGHT, new ConstantInteger(1L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable q2 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q2.getRef(), varValue, Operators.SHIFT_RIGHT, new ConstantInteger(2L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable q = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q.getRef(), q1.getRef(), Operators.PLUS, q2.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable q_s4 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q_s4.getRef(), q.getRef(), Operators.SHIFT_RIGHT, new ConstantInteger(4L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable q_add4 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q_add4.getRef(), q.getRef(), Operators.PLUS, q_s4.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable q_s8 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q_s8.getRef(), q_add4.getRef(), Operators.SHIFT_RIGHT, new ConstantInteger(8L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable q_add8 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q_add8.getRef(), q_add4.getRef(), Operators.PLUS, q_s8.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable q_final = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q_final.getRef(), q_add8.getRef(), Operators.SHIFT_RIGHT, new ConstantInteger(3L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable q_s3 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q_s3.getRef(), q_final.getRef(), Operators.SHIFT_LEFT, new ConstantInteger(3L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable q_s1 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q_s1.getRef(), q_final.getRef(), Operators.SHIFT_LEFT, new ConstantInteger(1L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable q_mul10 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q_mul10.getRef(), q_s3.getRef(), Operators.PLUS, q_s1.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable r = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) r.getRef(), varValue, Operators.MINUS, q_mul10.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable r_add3 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) r_add3.getRef(), r.getRef(), Operators.PLUS, new ConstantInteger(3L, SymbolType.WORD), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable corr = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) corr.getRef(), r_add3.getRef(), Operators.SHIFT_RIGHT, new ConstantInteger(3L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable q_s1_2 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q_s1_2.getRef(), q_final.getRef(), Operators.SHIFT_LEFT, new ConstantInteger(1L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable res = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) res.getRef(), q_s1_2.getRef(), Operators.PLUS, corr.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));

      return res.getRef();
   }

   private RValue emitDiv3Word(StatementAssignment assignment, ListIterator<Statement> stmtIt, RValue varValue, Scope scope) {
      Variable q1 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q1.getRef(), varValue, Operators.SHIFT_RIGHT, new ConstantInteger(1L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
      Variable q3 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q3.getRef(), varValue, Operators.SHIFT_RIGHT, new ConstantInteger(3L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
      Variable q = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q.getRef(), q1.getRef(), Operators.PLUS, q3.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable q_s4 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q_s4.getRef(), q.getRef(), Operators.SHIFT_RIGHT, new ConstantInteger(4L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
      Variable q_add4 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q_add4.getRef(), q.getRef(), Operators.PLUS, q_s4.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable q_s8 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q_s8.getRef(), q_add4.getRef(), Operators.SHIFT_RIGHT, new ConstantInteger(8L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
      Variable q_add8 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q_add8.getRef(), q_add4.getRef(), Operators.PLUS, q_s8.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable q_final = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q_final.getRef(), q_add8.getRef(), Operators.SHIFT_RIGHT, new ConstantInteger(1L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable q_s1 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q_s1.getRef(), q_final.getRef(), Operators.SHIFT_LEFT, new ConstantInteger(1L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
      Variable q_mul3 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q_mul3.getRef(), q_s1.getRef(), Operators.PLUS, q_final.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));
      Variable r = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) r.getRef(), varValue, Operators.MINUS, q_mul3.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable r_s3 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) r_s3.getRef(), r.getRef(), Operators.SHIFT_LEFT, new ConstantInteger(3L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
      Variable r_s1 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) r_s1.getRef(), r.getRef(), Operators.SHIFT_LEFT, new ConstantInteger(1L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));
      Variable r_sum1 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) r_sum1.getRef(), r_s3.getRef(), Operators.PLUS, r_s1.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));
      Variable r_mul11 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) r_mul11.getRef(), r_sum1.getRef(), Operators.PLUS, r.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable corr = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) corr.getRef(), r_mul11.getRef(), Operators.SHIFT_RIGHT, new ConstantInteger(5L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable res = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) res.getRef(), q_final.getRef(), Operators.PLUS, corr.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));

      return res.getRef();
   }

   private RValue emitDiv7Word(StatementAssignment assignment, ListIterator<Statement> stmtIt, RValue varValue, Scope scope) {
      Variable q1 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q1.getRef(), varValue, Operators.SHIFT_RIGHT, new ConstantInteger(3L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable q2 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q2.getRef(), varValue, Operators.SHIFT_RIGHT, new ConstantInteger(6L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable q = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q.getRef(), q1.getRef(), Operators.PLUS, q2.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable q_s6 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q_s6.getRef(), q.getRef(), Operators.SHIFT_RIGHT, new ConstantInteger(6L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable q_sum = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q_sum.getRef(), q.getRef(), Operators.PLUS, q_s6.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable q_s3 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q_s3.getRef(), q_sum.getRef(), Operators.SHIFT_LEFT, new ConstantInteger(3L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable q_mul7 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) q_mul7.getRef(), q_s3.getRef(), Operators.MINUS, q_sum.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable r = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) r.getRef(), varValue, Operators.MINUS, q_mul7.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable r_s3 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) r_s3.getRef(), r.getRef(), Operators.SHIFT_LEFT, new ConstantInteger(3L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable r_mul9 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) r_mul9.getRef(), r_s3.getRef(), Operators.PLUS, r.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable r_add7 = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) r_add7.getRef(), r_mul9.getRef(), Operators.PLUS, new ConstantInteger(7L, SymbolType.WORD), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable corr = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) corr.getRef(), r_add7.getRef(), Operators.SHIFT_RIGHT, new ConstantInteger(6L, SymbolType.BYTE), true, assignment.getSource(), Comment.NO_COMMENTS));

      Variable res = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment((LValue) res.getRef(), q_sum.getRef(), Operators.PLUS, corr.getRef(), true, assignment.getSource(), Comment.NO_COMMENTS));

      return res.getRef();
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
               inlinedDivCounts.put(constValue, inlinedDivCounts.getOrDefault(constValue, 0) + 1);
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
         } else if(varType.getSizeBytes() == 2) {
            stmtIt.previous();
            RValue finalVal = emitDivConstWordRecursive(assignment, stmtIt, varValue, scope, constValue);
            stmtIt.next();
            if(finalVal != null) {
               inlinedDivCounts.put(constValue, inlinedDivCounts.getOrDefault(constValue, 0) + 1);
               getLog().append("Rewriting 16-bit division by " + constValue + " to fast shift-add sequence: " + assignment.toString(getProgram(), false));
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
