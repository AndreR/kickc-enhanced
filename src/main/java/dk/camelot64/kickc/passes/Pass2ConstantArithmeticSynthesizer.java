package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.Comment;
import dk.camelot64.kickc.model.ConstantNotLiteral;
import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.VariableBuilder;
import dk.camelot64.kickc.model.operators.Operator;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeInference;
import dk.camelot64.kickc.model.types.SymbolTypeIntegerFixed;
import dk.camelot64.kickc.model.values.CastValue;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.model.values.ConstantLiteral;
import dk.camelot64.kickc.model.values.ConstantValue;
import dk.camelot64.kickc.model.values.LValue;
import dk.camelot64.kickc.model.values.RValue;
import dk.camelot64.kickc.model.values.VariableRef;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Constant-specialized arithmetic synthesizer for 6502 SSA IR:
 *
 * - Part A (Multiplication): Decomposes non-power-of-two constant multiplications `x * C`
 *   (8-bit and 16-bit non-volatile SSA assignments) via optimal Binary/NAF shift-add-sub
 *   or factored shift-add-sub chains (at most 4 add/sub steps).
 * - Part B (8-bit Unsigned Division & Modulo): Computes exact compile-time reciprocal
 *   multipliers `(M, S)` verified exhaustively across all 256 byte inputs `x in [0, 255]`
 *   such that `((x * M) >> S) == (x / C)`, lowering `x / C` via 16-bit shift-add +
 *   `BYTE1` high-byte extraction and `x % C` via `x - (x / C) * C`.
 */
public class Pass2ConstantArithmeticSynthesizer extends Pass2SsaOptimization {

   private static final int MAX_ADD_SUB_STEPS = 4;

   public static final class Term {
      public final int pos;
      public final int sign; // +1 or -1

      public Term(int pos, int sign) {
         this.pos = pos;
         this.sign = sign;
      }
   }

   public interface MulPlan {
      int addSubCount();
      int totalShiftCount();
      int maxStepShift();
      long eval(long x);
   }

   public static final class SingleStagePlan implements MulPlan {
      public final List<Term> terms; // ascending pos, last term always sign +1
      public final int finalShift;

      public SingleStagePlan(List<Term> terms, int finalShift) {
         this.terms = terms;
         this.finalShift = finalShift;
      }

      @Override
      public int addSubCount() {
         return Math.max(0, terms.size() - 1);
      }

      @Override
      public int totalShiftCount() {
         int sum = finalShift + terms.get(0).pos;
         for(int i = terms.size() - 2; i >= 0; i--) {
            sum += terms.get(i + 1).pos - terms.get(i).pos;
         }
         return sum;
      }

      @Override
      public int maxStepShift() {
         int m = finalShift + terms.get(0).pos;
         for(int i = terms.size() - 2; i >= 0; i--) {
            int d = terms.get(i + 1).pos - terms.get(i).pos;
            if(d > m) m = d;
         }
         return m;
      }

      @Override
      public long eval(long x) {
         long acc = x;
         for(int i = terms.size() - 2; i >= 0; i--) {
            int delta = terms.get(i + 1).pos - terms.get(i).pos;
            if(delta > 0) acc = acc << delta;
            acc = (terms.get(i).sign > 0) ? (acc + x) : (acc - x);
         }
         int tailShift = terms.get(0).pos + finalShift;
         if(tailShift > 0) acc = acc << tailShift;
         return acc;
      }
   }

   public static final class FactoredPlan implements MulPlan {
      public final MulPlan first;
      public final MulPlan second;

      public FactoredPlan(MulPlan first, MulPlan second) {
         this.first = first;
         this.second = second;
      }

      @Override
      public int addSubCount() {
         return first.addSubCount() + second.addSubCount();
      }

      @Override
      public int totalShiftCount() {
         return first.totalShiftCount() + second.totalShiftCount();
      }

      @Override
      public int maxStepShift() {
         return Math.max(first.maxStepShift(), second.maxStepShift());
      }

      @Override
      public long eval(long x) {
         return second.eval(first.eval(x));
      }
   }

   public static final class Reciprocal {
      public final long m;
      public final int s;

      public Reciprocal(long m, int s) {
         this.m = m;
         this.s = s;
      }
   }

   private static final Reciprocal NO_RECIPROCAL = new Reciprocal(0L, 0);
   private static final Map<Long, Reciprocal> RECIP_CACHE = new ConcurrentHashMap<>();

   public Pass2ConstantArithmeticSynthesizer(Program program) {
      super(program);
   }

   public static boolean isPowerOfTwo(long c) {
      return c > 0L && (c & (c - 1L)) == 0L;
   }

   /**
    * Computes an optimal Binary/NAF (possibly factored) shift-add-sub multiplication plan
    * for positive non-power-of-two constant {@code c} within {@code maxBits} width (8 or 16).
    * Returns {@code null} if {@code c <= 1}, is a power of 2, or requires > 4 add/sub steps.
    */
   public static MulPlan findMultiplyPlan(long c, int maxBits) {
      if(c <= 1L || isPowerOfTwo(c)) {
         return null;
      }
      int tz = Long.numberOfTrailingZeros(c);
      long odd = c >>> tz;
      if(odd <= 1L) {
         return null;
      }

      MulPlan best = null;
      SingleStagePlan direct = bestSingleStageOddPlan(odd, tz, maxBits);
      if(direct != null && direct.addSubCount() <= MAX_ADD_SUB_STEPS) {
         best = direct;
      }

      // If direct NAF/binary needs > 2 add/sub steps (e.g. 171 = 9*19, 205 = 5*41), try factoring odd = f1 * f2
      if(odd >= 9L && (best == null || best.addSubCount() > 2)) {
         for(long f1 = 3L; f1 * f1 <= odd; f1 += 2L) {
            if(odd % f1 != 0L) continue;
            long f2 = odd / f1;
            SingleStagePlan p1 = bestSingleStageOddPlan(f1, 0, maxBits);
            SingleStagePlan p2 = bestSingleStageOddPlan(f2, tz, maxBits);
            if(p1 != null && p2 != null) {
               int steps = p1.addSubCount() + p2.addSubCount();
               if(steps <= MAX_ADD_SUB_STEPS) {
                  FactoredPlan cand = new FactoredPlan(p1, p2);
                  if(isBetterPlan(cand, best)) {
                     best = cand;
                  }
               }
            }
         }
      }

      return best;
   }

   private static SingleStagePlan bestSingleStageOddPlan(long odd, int finalShift, int maxBits) {
      if(odd < 3L || (odd & 1L) == 0L) return null;

      SingleStagePlan binary = buildBinaryOddPlan(odd, finalShift, maxBits);
      SingleStagePlan naf = buildNafOddPlan(odd, finalShift, maxBits);

      SingleStagePlan best = null;
      if(binary != null && isBetterPlan(binary, best)) best = binary;
      if(naf != null && isBetterPlan(naf, best)) best = naf;
      return best;
   }

   private static SingleStagePlan buildBinaryOddPlan(long odd, int finalShift, int maxBits) {
      List<Term> terms = new ArrayList<>();
      long n = odd;
      int pos = 0;
      while(n != 0L) {
         if((n & 1L) != 0L) {
            terms.add(new Term(pos, 1));
         }
         n >>>= 1;
         pos++;
      }
      if(terms.size() < 2) return null;
      SingleStagePlan plan = new SingleStagePlan(terms, finalShift);
      return (plan.maxStepShift() < maxBits) ? plan : null;
   }

   private static SingleStagePlan buildNafOddPlan(long odd, int finalShift, int maxBits) {
      List<Term> terms = new ArrayList<>();
      long n = odd;
      int pos = 0;
      while(n > 0L) {
         if((n & 1L) != 0L) {
            int digit = (int) (2L - (n & 3L)); // +1 if n%4==1, -1 if n%4==3
            n -= digit;
            terms.add(new Term(pos, digit));
         }
         n >>>= 1;
         pos++;
      }
      if(terms.size() < 2 || terms.get(terms.size() - 1).sign <= 0) return null;
      SingleStagePlan plan = new SingleStagePlan(terms, finalShift);
      return (plan.maxStepShift() < maxBits) ? plan : null;
   }

   private static boolean isBetterPlan(MulPlan cand, MulPlan currentBest) {
      if(currentBest == null) return true;
      int cmpSteps = Integer.compare(cand.addSubCount(), currentBest.addSubCount());
      if(cmpSteps != 0) return cmpSteps < 0;
      return Integer.compare(cand.totalShiftCount(), currentBest.totalShiftCount()) < 0;
   }

   /**
    * Computes an exact unsigned 8-bit reciprocal pair {@code (M, S)} for {@code d in [3, 255]}
    * (excluding powers of 2) with {@code S in [8, 15]} and {@code 255 * M <= 65535},
    * exhaustively verified across all {@code x in [0, 255]}.
    */
   public static Reciprocal findReciprocal(long d) {
      if(d <= 2L || d > 255L || isPowerOfTwo(d)) {
         return null;
      }
      Reciprocal cached = RECIP_CACHE.get(d);
      if(cached != null) {
         return (cached == NO_RECIPROCAL) ? null : cached;
      }

      // First prefer 8-bit multipliers M in [1, 255], S in [8, 15]
      for(int s = 8; s <= 15; s++) {
         long mApprox = (1L << s) / d;
         for(long m = Math.max(1L, mApprox - 1L); m <= Math.min(255L, mApprox + 2L); m++) {
            if(isExactReciprocal(d, m, s)) {
               Reciprocal r = new Reciprocal(m, s);
               RECIP_CACHE.putIfAbsent(d, r);
               return r;
            }
         }
      }

      // Fallback check for M in [256, 511] if 255 * M still fits in 16-bit WORD
      for(int s = 8; s <= 15; s++) {
         long mApprox = (1L << s) / d;
         for(long m = Math.max(256L, mApprox - 1L); m <= Math.min(511L, mApprox + 2L); m++) {
            if(255L * m <= 65535L && isExactReciprocal(d, m, s)) {
               Reciprocal r = new Reciprocal(m, s);
               RECIP_CACHE.putIfAbsent(d, r);
               return r;
            }
         }
      }

      RECIP_CACHE.putIfAbsent(d, NO_RECIPROCAL);
      return null;
   }

   public static boolean isExactReciprocal(long d, long m, int s) {
      if(d <= 0L || m <= 0L || s < 0 || s >= 31) return false;
      if(255L * m > 65535L) return false;
      for(int x = 0; x < 256; x++) {
         if(((x * m) >>> s) != (x / d)) {
            return false;
         }
      }
      return true;
   }

   @Override
   public boolean step() {
      boolean modified = false;
      for(Graph.Block block : getGraph().getAllBlocks()) {
         Scope scope = getProgramScope().getScope(block.getScope());
         ListIterator<Statement> stmtIt = block.getStatements().listIterator();
         while(stmtIt.hasNext()) {
            Statement stmt = stmtIt.next();
            if(!(stmt instanceof StatementAssignment assign)) continue;
            if(!(assign.getlValue() instanceof VariableRef dstRef)) continue;

            Variable dstVar = getProgramScope().getVariable(dstRef);
            if(dstVar == null || dstVar.isVolatile() || dstVar.isKindLoadStore()) continue;

            Operator op = assign.getOperator();
            if(Operators.MULTIPLY.equals(op)) {
               if(tryRewriteMultiply(assign, stmtIt, scope)) {
                  modified = true;
               }
            } else if(Operators.DIVIDE.equals(op)) {
               if(tryRewriteUnsignedByteDivide(assign, stmtIt, scope)) {
                  modified = true;
               }
            } else if(Operators.MODULO.equals(op)) {
               if(tryRewriteUnsignedByteModulo(assign, stmtIt, scope)) {
                  modified = true;
               }
            }
         }
      }

      if(modified) {
         getProgram().clearVariableReferenceInfos();
         getProgram().clearStatementInfos();
      }
      return modified;
   }

   private boolean tryRewriteMultiply(StatementAssignment assign, ListIterator<Statement> stmtIt, Scope scope) {
      RValue varVal = null;
      Long constVal = null;

      if(assign.getrValue2() instanceof ConstantValue && !(assign.getrValue1() instanceof ConstantValue)) {
         varVal = assign.getrValue1();
         constVal = getConstantInteger(assign.getrValue2());
      } else if(assign.getrValue1() instanceof ConstantValue && !(assign.getrValue2() instanceof ConstantValue)) {
         varVal = assign.getrValue2();
         constVal = getConstantInteger(assign.getrValue1());
      }
      if(varVal == null || constVal == null) return false;
      if(constVal <= 1L || isPowerOfTwo(constVal)) return false;
      if(isVolatileOperand(varVal)) return false;

      SymbolType dstType = SymbolTypeInference.inferType(getProgramScope(), assign.getlValue());
      SymbolType varType = SymbolTypeInference.inferType(getProgramScope(), varVal);
      if(!(dstType instanceof SymbolTypeIntegerFixed dstFixed) || !(varType instanceof SymbolTypeIntegerFixed varFixed)) {
         return false;
      }
      int dstBytes = dstFixed.getSizeBytes();
      int varBytes = varFixed.getSizeBytes();
      if((dstBytes != 1 && dstBytes != 2) || (varBytes != 1 && varBytes != 2)) {
         return false;
      }

      SymbolTypeIntegerFixed workType;
      if(dstBytes == 2 || varBytes == 2) {
         workType = (dstBytes == 2) ? (SymbolTypeIntegerFixed) dstFixed.getQualified(false, false) : SymbolType.WORD;
      } else {
         workType = (SymbolTypeIntegerFixed) dstFixed.getQualified(false, false);
      }

      // Special 8-bit case: x * 255 == -x mod 256
      if(workType.getSizeBytes() == 1 && (constVal & 0xFFL) == 255L) {
         RValue base = coerceToType(varVal, workType, stmtIt, assign, scope, true);
         assign.setrValue1(null);
         assign.setOperator(Operators.NEG);
         assign.setrValue2(base);
         getLog().append("Synthesized 8-bit *255 via unary negation: " + assign.toString(getProgram(), false));
         return true;
      }

      MulPlan plan = findMultiplyPlan(constVal, workType.getBits());
      if(plan == null) return false;

      stmtIt.previous();
      RValue base = coerceToType(varVal, workType, stmtIt, assign, scope, false);
      RValue resultVal = emitMulPlan(plan, base, workType, assign, stmtIt, scope);
      stmtIt.next();

      RValue finalRhs = resultVal;
      if(!dstFixed.equals(workType)) {
         finalRhs = new CastValue(dstFixed, resultVal);
      }
      assign.setrValue1(null);
      assign.setOperator(null);
      assign.setrValue2(finalRhs);
      getLog().append("Synthesized constant multiply *" + constVal + " (" + plan.addSubCount() + " add/sub ops): " + assign.toString(getProgram(), false));
      return true;
   }

   private boolean tryRewriteUnsignedByteDivide(StatementAssignment assign, ListIterator<Statement> stmtIt, Scope scope) {
      if(!(assign.getrValue2() instanceof ConstantValue)) return false;
      Long d = getConstantInteger(assign.getrValue2());
      if(d == null || d <= 2L || d > 255L || isPowerOfTwo(d)) return false;

      RValue varVal = assign.getrValue1();
      if(isVolatileOperand(varVal)) return false;
      SymbolType varType = SymbolTypeInference.inferType(getProgramScope(), varVal);
      if(!SymbolType.BYTE.equals(varType)) return false;

      Reciprocal recip = findReciprocal(d);
      if(recip == null) return false;

      MulPlan mulPlan = isPowerOfTwo(recip.m) ? null : findMultiplyPlan(recip.m, 16);
      if(!isPowerOfTwo(recip.m) && mulPlan == null) return false;

      SymbolType dstType = SymbolTypeInference.inferType(getProgramScope(), assign.getlValue());
      stmtIt.previous();
      RValue qVal = emitReciprocalDivideSteps(varVal, recip, mulPlan, assign, stmtIt, scope);
      stmtIt.next();

      assign.setrValue1(null);
      assign.setOperator(null);
      assign.setrValue2(SymbolType.BYTE.equals(dstType) ? qVal : new CastValue(dstType, qVal));
      getLog().append("Synthesized unsigned 8-bit division /" + d + " (M=" + recip.m + ", S=" + recip.s + "): " + assign.toString(getProgram(), false));
      return true;
   }

   private boolean tryRewriteUnsignedByteModulo(StatementAssignment assign, ListIterator<Statement> stmtIt, Scope scope) {
      if(!(assign.getrValue2() instanceof ConstantValue)) return false;
      Long d = getConstantInteger(assign.getrValue2());
      if(d == null || d <= 2L || d > 255L || isPowerOfTwo(d)) return false;

      RValue varVal = assign.getrValue1();
      if(isVolatileOperand(varVal)) return false;
      SymbolType varType = SymbolTypeInference.inferType(getProgramScope(), varVal);
      if(!SymbolType.BYTE.equals(varType)) return false;

      Reciprocal recip = findReciprocal(d);
      if(recip == null) return false;

      MulPlan recipMulPlan = isPowerOfTwo(recip.m) ? null : findMultiplyPlan(recip.m, 16);
      MulPlan backMulPlan = findMultiplyPlan(d, 8);
      if(!isPowerOfTwo(recip.m) && recipMulPlan == null) return false;
      if(backMulPlan == null) return false;

      SymbolType dstType = SymbolTypeInference.inferType(getProgramScope(), assign.getlValue());
      stmtIt.previous();

      // 1. q = x / d via reciprocal multiplication + BYTE1 + shift
      RValue qVal = emitReciprocalDivideSteps(varVal, recip, recipMulPlan, assign, stmtIt, scope);
      // 2. prod = q * d in 8-bit BYTE via shift-add-sub
      RValue prodVal = emitMulPlan(backMulPlan, qVal, SymbolType.BYTE, assign, stmtIt, scope);

      if(SymbolType.BYTE.equals(dstType)) {
         stmtIt.next();
         assign.setrValue1(varVal);
         assign.setOperator(Operators.MINUS);
         assign.setrValue2(prodVal);
      } else {
         Variable varRem = VariableBuilder.createIntermediate(scope, SymbolType.BYTE, getProgram());
         stmtIt.add(new StatementAssignment((LValue) varRem.getRef(), varVal, Operators.MINUS, prodVal, true, assign.getSource(), Comment.NO_COMMENTS));
         stmtIt.next();
         assign.setrValue1(null);
         assign.setOperator(null);
         assign.setrValue2(new CastValue(dstType, varRem.getRef()));
      }

      getLog().append("Synthesized unsigned 8-bit modulo %" + d + " (M=" + recip.m + ", S=" + recip.s + "): " + assign.toString(getProgram(), false));
      return true;
   }

   private RValue emitReciprocalDivideSteps(
         RValue byteVal,
         Reciprocal recip,
         MulPlan mulPlan,
         StatementAssignment contextAssign,
         ListIterator<Statement> stmtIt,
         Scope scope) {
      Variable varPromote = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
      stmtIt.add(new StatementAssignment(
            (LValue) varPromote.getRef(), null, null,
            new CastValue(SymbolType.WORD, byteVal),
            true, contextAssign.getSource(), Comment.NO_COMMENTS));

      RValue mulResult;
      if(isPowerOfTwo(recip.m)) {
         int sh = Long.numberOfTrailingZeros(recip.m);
         Variable varSh = VariableBuilder.createIntermediate(scope, SymbolType.WORD, getProgram());
         stmtIt.add(new StatementAssignment(
               (LValue) varSh.getRef(), varPromote.getRef(), Operators.SHIFT_LEFT,
               new ConstantInteger((long) sh, SymbolType.BYTE),
               true, contextAssign.getSource(), Comment.NO_COMMENTS));
         mulResult = varSh.getRef();
      } else {
         mulResult = emitMulPlan(mulPlan, varPromote.getRef(), SymbolType.WORD, contextAssign, stmtIt, scope);
      }

      Variable varHi = VariableBuilder.createIntermediate(scope, SymbolType.BYTE, getProgram());
      stmtIt.add(new StatementAssignment(
            (LValue) varHi.getRef(), null, Operators.BYTE1, mulResult,
            true, contextAssign.getSource(), Comment.NO_COMMENTS));

      if(recip.s <= 8) {
         return varHi.getRef();
      }
      int remShift = recip.s - 8;
      Variable varQuot = VariableBuilder.createIntermediate(scope, SymbolType.BYTE, getProgram());
      stmtIt.add(new StatementAssignment(
            (LValue) varQuot.getRef(), varHi.getRef(), Operators.SHIFT_RIGHT,
            new ConstantInteger((long) remShift, SymbolType.BYTE),
            true, contextAssign.getSource(), Comment.NO_COMMENTS));
      return varQuot.getRef();
   }

   private RValue emitMulPlan(
         MulPlan plan,
         RValue baseVal,
         SymbolTypeIntegerFixed workType,
         StatementAssignment contextAssign,
         ListIterator<Statement> stmtIt,
         Scope scope) {
      if(plan instanceof FactoredPlan fp) {
         RValue mid = emitMulPlan(fp.first, baseVal, workType, contextAssign, stmtIt, scope);
         return emitMulPlan(fp.second, mid, workType, contextAssign, stmtIt, scope);
      }
      SingleStagePlan sp = (SingleStagePlan) plan;
      RValue acc = baseVal;
      for(int i = sp.terms.size() - 2; i >= 0; i--) {
         int delta = sp.terms.get(i + 1).pos - sp.terms.get(i).pos;
         if(delta > 0) {
            Variable varShift = VariableBuilder.createIntermediate(scope, workType, getProgram());
            stmtIt.add(new StatementAssignment(
                  (LValue) varShift.getRef(), acc, Operators.SHIFT_LEFT,
                  new ConstantInteger((long) delta, SymbolType.BYTE),
                  true, contextAssign.getSource(), Comment.NO_COMMENTS));
            acc = varShift.getRef();
         }
         Operator addOrSub = (sp.terms.get(i).sign > 0) ? Operators.PLUS : Operators.MINUS;
         Variable varAcc = VariableBuilder.createIntermediate(scope, workType, getProgram());
         stmtIt.add(new StatementAssignment(
               (LValue) varAcc.getRef(), acc, addOrSub, baseVal,
               true, contextAssign.getSource(), Comment.NO_COMMENTS));
         acc = varAcc.getRef();
      }
      int tailShift = sp.terms.get(0).pos + sp.finalShift;
      if(tailShift > 0) {
         Variable varTail = VariableBuilder.createIntermediate(scope, workType, getProgram());
         stmtIt.add(new StatementAssignment(
               (LValue) varTail.getRef(), acc, Operators.SHIFT_LEFT,
               new ConstantInteger((long) tailShift, SymbolType.BYTE),
               true, contextAssign.getSource(), Comment.NO_COMMENTS));
         acc = varTail.getRef();
      }
      return acc;
   }

   private RValue coerceToType(
         RValue val,
         SymbolTypeIntegerFixed targetType,
         ListIterator<Statement> stmtIt,
         StatementAssignment contextAssign,
         Scope scope,
         boolean rewindBeforeContext) {
      SymbolType currentType = SymbolTypeInference.inferType(getProgramScope(), val);
      if(targetType.equals(currentType)) {
         return val;
      }
      if(rewindBeforeContext) stmtIt.previous();
      Variable coerced = VariableBuilder.createIntermediate(scope, targetType, getProgram());
      stmtIt.add(new StatementAssignment(
            (LValue) coerced.getRef(), null, null,
            new CastValue(targetType, val),
            true, contextAssign.getSource(), Comment.NO_COMMENTS));
      if(rewindBeforeContext) stmtIt.next();
      return coerced.getRef();
   }

   private boolean isVolatileOperand(RValue val) {
      if(val instanceof VariableRef vr) {
         Variable var = getProgramScope().getVariable(vr);
         return var != null && var.isVolatile();
      }
      return false;
   }

   private Long getConstantInteger(RValue rValue) {
      if(rValue instanceof ConstantValue cv) {
         try {
            ConstantLiteral lit = cv.calculateLiteral(getProgramScope());
            if(lit instanceof ConstantInteger ci) {
               return ci.getInteger();
            }
         } catch(ConstantNotLiteral ignored) {
         }
      }
      return null;
   }
}
