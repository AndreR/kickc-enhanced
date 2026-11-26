package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.ConstantNotLiteral;
import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.StatementInfos;
import dk.camelot64.kickc.model.VariableBuilder;
import dk.camelot64.kickc.model.VariableReferenceInfos;
import dk.camelot64.kickc.model.iterator.ProgramValueIterator;
import dk.camelot64.kickc.model.operators.Operator;
import dk.camelot64.kickc.model.operators.OperatorCast;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.statements.StatementLValue;
import dk.camelot64.kickc.model.statements.StatementPhiBlock;
import dk.camelot64.kickc.model.symbols.ProgramScope;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeInference;
import dk.camelot64.kickc.model.types.SymbolTypeIntegerFixed;
import dk.camelot64.kickc.model.values.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

/**
 * Value Range Propagation (VRP) and 16-to-8 bit expression narrowing in SSA form.
 * Computes conservative [min, max] integer intervals over SSA statements and PHI nodes
 * in a bounded fixpoint loop (up to 4 rounds), narrowing 16-bit WORD/SWORD expressions
 * whose operands and results statically fit in 8-bit BYTE or SBYTE.
 * Also preserves the specialized (int)u1 - (int)u2 SBYTE difference narrowing rule.
 */
public class Pass2RangeNarrowing extends Pass2SsaOptimization {

   private static final int MAX_FIXPOINT_ROUNDS = 4;

   public static final class Interval {
      public final long min;
      public final long max;

      public Interval(long min, long max) {
         this.min = min;
         this.max = max;
      }

      public boolean isConstant() {
         return min == max;
      }

      public boolean fitsIn(long lo, long hi) {
         return min >= lo && max <= hi;
      }

      public Interval union(Interval other) {
         return new Interval(Math.min(this.min, other.min), Math.max(this.max, other.max));
      }

      @Override
      public boolean equals(Object o) {
         if(this == o) return true;
         if(!(o instanceof Interval other)) return false;
         return min == other.min && max == other.max;
      }

      @Override
      public int hashCode() {
         return 31 * Long.hashCode(min) + Long.hashCode(max);
      }

      @Override
      public String toString() {
         return "[" + min + ", " + max + "]";
      }
   }

   public Pass2RangeNarrowing(Program program) {
      super(program);
   }

   private static class StmtLookup {
      private final Program program;
      private final Graph graph;
      private StatementInfos infos;
      private boolean refreshed;

      StmtLookup(Program program, Graph graph) {
         this.program = program;
         this.graph = graph;
      }

      Statement get(int idx) {
         if(infos == null) {
            infos = program.getStatementInfos();
         }
         if(infos == null) {
            return graph.getStatementByIndex(idx);
         }
         Statement stmt = infos.getStatement(idx);
         if((stmt != null && !Integer.valueOf(idx).equals(stmt.getIndex()))
               || (stmt == null && !refreshed)) {
            program.clearStatementInfos();
            infos = program.getStatementInfos();
            refreshed = true;
            stmt = (infos != null) ? infos.getStatement(idx) : graph.getStatementByIndex(idx);
         }
         return stmt;
      }
   }

   @Override
   public boolean step() {
      boolean modified = false;
      VariableReferenceInfos refInfos = getProgram().getVariableReferenceInfos();
      StmtLookup stmtLookup = new StmtLookup(getProgram(), getGraph());
      ProgramScope scope = getProgramScope();

      Set<VariableRef> addressTaken = collectAddressTaken(getProgram());
      Map<VariableRef, Integer> defCounts = collectDefCounts(getGraph());
      Map<VariableRef, Interval> env = computeVariableIntervals(getProgram(), scope, addressTaken, defCounts);

      Map<VariableRef, VariableRef> narrowedToVar = new HashMap<>();
      Map<VariableRef, SymbolTypeIntegerFixed> narrowedToType = new HashMap<>();

      for(Graph.Block block : getGraph().getAllBlocks()) {
         Scope blockScope = scope.getScope(block.getScope());
         ListIterator<Statement> it = block.getStatements().listIterator();
         while(it.hasNext()) {
            Statement stmt = it.next();
            if(!(stmt instanceof StatementAssignment assign)) continue;
            if(!(assign.getlValue() instanceof VariableRef dstRef)) continue;
            Operator op = assign.getOperator();
            if(op == null || assign.getrValue1() == null || assign.getrValue2() == null) continue;

            SymbolType dstType = SymbolTypeInference.inferType(scope, dstRef);
            if(!SymbolType.WORD.equals(dstType) && !SymbolType.SWORD.equals(dstType)) continue;

            Variable dstVar = scope.getVariable(dstRef);
            if(dstVar == null || dstVar.isVolatile() || dstVar.isKindLoadStore() || dstVar.isKindConstant()) continue;
            if(addressTaken.contains(dstRef) || defCounts.getOrDefault(dstRef, 0) != 1) continue;

            // Preserve existing OPT-05 rule: (int)u1 - (int)u2 with u1, u2 in [0, 127] -> SBYTE subtraction
            if(Operators.MINUS.equals(op) && SymbolType.SWORD.equals(dstType)) {
               RValue u1 = unwrapUnsignedByteCast(assign.getrValue1(), refInfos, stmtLookup);
               RValue u2 = unwrapUnsignedByteCast(assign.getrValue2(), refInfos, stmtLookup);
               if(u1 != null && u2 != null) {
                  int max1 = inferUnsignedByteMax(u1, refInfos, stmtLookup, 0);
                  int max2 = inferUnsignedByteMax(u2, refInfos, stmtLookup, 0);
                  if(max1 <= 127 && max2 <= 127) {
                     Variable sbyteVar = VariableBuilder.createIntermediate(blockScope, SymbolType.SBYTE, getProgram());
                     RValue s1 = (u1 instanceof ConstantInteger ci) ? new ConstantInteger(ci.getInteger(), SymbolType.SBYTE) : new CastValue(SymbolType.SBYTE, u1);
                     RValue s2 = (u2 instanceof ConstantInteger ci) ? new ConstantInteger(ci.getInteger(), SymbolType.SBYTE) : new CastValue(SymbolType.SBYTE, u2);
                     StatementAssignment narrowAssign = new StatementAssignment(
                           sbyteVar.getVariableRef(), s1, Operators.MINUS, s2,
                           assign.isInitialAssignment(), assign.getSource(), assign.getComments());

                     it.previous();
                     it.add(narrowAssign);
                     it.next();
                     assign.setrValue1(null);
                     assign.setOperator(null);
                     assign.setrValue2(new CastValue(SymbolType.SWORD, sbyteVar.getVariableRef()));
                     narrowedToVar.put(dstRef, sbyteVar.getVariableRef());
                     narrowedToType.put(dstRef, SymbolType.SBYTE);
                     getLog().append("OPT-05: Narrowed 16-bit signed difference to 8-bit SBYTE: " + narrowAssign.toString(getProgram(), false));
                     modified = true;
                     continue;
                  }
               }
            }

            // General VRP 16-to-8 bit expression narrowing on +, -, &, |, ^, >>
            if(!isNarrowableBinaryOp(op)) continue;

            Interval i1 = evalRValue(assign.getrValue1(), env, scope);
            Interval i2 = evalRValue(assign.getrValue2(), env, scope);
            Interval res = env.get(dstRef);
            if(res == null) {
               res = evalAssignment(assign, dstType, env, scope);
            }
            if(i1 == null || i2 == null || res == null) continue;

            SymbolTypeIntegerFixed narrowType = selectNarrowType(op, i1, i2, res);
            if(narrowType == null) continue;

            Variable narrowVar = VariableBuilder.createIntermediate(blockScope, narrowType, getProgram());
            RValue n1 = unwrapOrCastNarrow(assign.getrValue1(), narrowType, refInfos, stmtLookup, narrowedToVar, narrowedToType);
            SymbolTypeIntegerFixed op2Type = Operators.SHIFT_RIGHT.equals(op) ? SymbolType.BYTE : narrowType;
            RValue n2 = unwrapOrCastNarrow(assign.getrValue2(), op2Type, refInfos, stmtLookup, narrowedToVar, narrowedToType);

            StatementAssignment narrowAssign = new StatementAssignment(
                  narrowVar.getVariableRef(), n1, op, n2,
                  assign.isInitialAssignment(), assign.getSource(), assign.getComments());

            it.previous();
            it.add(narrowAssign);
            it.next();
            assign.setrValue1(null);
            assign.setOperator(null);
            assign.setrValue2(new CastValue(dstType, narrowVar.getVariableRef()));
            narrowedToVar.put(dstRef, narrowVar.getVariableRef());
            narrowedToType.put(dstRef, narrowType);
            getLog().append("VRP narrowed 16-bit expression (" + res + ") to 8-bit " + narrowType + ": " + narrowAssign.toString(getProgram(), false));
            modified = true;
         }
      }

      if(!narrowedToVar.isEmpty()) {
         simplifyNarrowedCasts(narrowedToVar, narrowedToType);
      }

      if(modified) {
         getProgram().clearVariableReferenceInfos();
         getProgram().clearStatementInfos();
      }
      return modified;
   }

   /**
    * Computes conservative [min, max] integer intervals for all eligible SSA variables
    * in the program using a bounded fixpoint iteration.
    */
   public static Map<VariableRef, Interval> computeVariableIntervals(Program program) {
      return computeVariableIntervals(program, program.getScope(), collectAddressTaken(program), collectDefCounts(program.getGraph()));
   }

   private static Map<VariableRef, Interval> computeVariableIntervals(
         Program program, ProgramScope scope, Set<VariableRef> addressTaken, Map<VariableRef, Integer> defCounts) {
      Map<VariableRef, Interval> env = new HashMap<>();
      Graph graph = program.getGraph();

      // Initialize all referenced/defined integer variables to their type bounds (sound top)
      for(Graph.Block block : graph.getAllBlocks()) {
         for(Statement stmt : block.getStatements()) {
            if(stmt instanceof StatementPhiBlock phi) {
               for(StatementPhiBlock.PhiVariable pv : phi.getPhiVariables()) {
                  initVarInterval(pv.getVariable(), env, scope);
                  for(StatementPhiBlock.PhiRValue prv : pv.getValues()) {
                     if(prv.getrValue() instanceof VariableRef vr) {
                        initVarInterval(vr, env, scope);
                     }
                  }
               }
            } else if(stmt instanceof StatementAssignment assign) {
               if(assign.getlValue() instanceof VariableRef vr) {
                  initVarInterval(vr, env, scope);
               }
               if(assign.getrValue1() instanceof VariableRef vr) {
                  initVarInterval(vr, env, scope);
               }
               if(assign.getrValue2() instanceof VariableRef vr) {
                  initVarInterval(vr, env, scope);
               }
            }
         }
      }

      for(int round = 0; round < MAX_FIXPOINT_ROUNDS; round++) {
         boolean changed = false;
         for(Graph.Block block : graph.getAllBlocks()) {
            for(Statement stmt : block.getStatements()) {
               if(stmt instanceof StatementPhiBlock phi) {
                  for(StatementPhiBlock.PhiVariable pv : phi.getPhiVariables()) {
                     VariableRef dst = pv.getVariable();
                     if(!isEligibleSsa(dst, scope, addressTaken, defCounts)) continue;
                     Interval bounds = typeBounds(SymbolTypeInference.inferType(scope, dst));
                     if(bounds == null) continue;
                     List<StatementPhiBlock.PhiRValue> vals = pv.getValues();
                     if(vals.isEmpty()) continue;

                     Interval union = null;
                     boolean allKnown = true;
                     for(StatementPhiBlock.PhiRValue prv : vals) {
                        Interval iv = evalRValue(prv.getrValue(), env, scope);
                        if(iv == null) {
                           allKnown = false;
                           break;
                        }
                        union = (union == null) ? iv : union.union(iv);
                     }
                     Interval next = (allKnown && union != null && union.fitsIn(bounds.min, bounds.max)) ? union : bounds;
                     Interval prev = env.get(dst);
                     if(!next.equals(prev)) {
                        env.put(dst, next);
                        changed = true;
                     }
                  }
               } else if(stmt instanceof StatementAssignment assign) {
                  if(!(assign.getlValue() instanceof VariableRef dst)) continue;
                  if(!isEligibleSsa(dst, scope, addressTaken, defCounts)) continue;
                  SymbolType dstType = SymbolTypeInference.inferType(scope, dst);
                  Interval next = evalAssignment(assign, dstType, env, scope);
                  if(next == null) continue;
                  Interval prev = env.get(dst);
                  if(!next.equals(prev)) {
                     env.put(dst, next);
                     changed = true;
                  }
               }
            }
         }
         if(!changed) break;
      }

      return env;
   }

   private static void initVarInterval(VariableRef vr, Map<VariableRef, Interval> env, ProgramScope scope) {
      if(vr == null || env.containsKey(vr)) return;
      Interval bounds = typeBounds(SymbolTypeInference.inferType(scope, vr));
      if(bounds != null) {
         env.put(vr, bounds);
      }
   }

   private static Set<VariableRef> collectAddressTaken(Program program) {
      Set<VariableRef> addressTaken = new HashSet<>();
      ProgramValueIterator.execute(program.getGraph(), (programValue, currentStmt, stmtIt, currentBlock) -> {
         Value val = programValue.get();
         if(val instanceof ConstantSymbolPointer csp) {
            SymbolRef sym = csp.getToSymbol();
            if(sym instanceof VariableRef vr) {
               addressTaken.add(vr);
            }
         }
      });
      for(Graph.Block block : program.getGraph().getAllBlocks()) {
         for(Statement stmt : block.getStatements()) {
            if(stmt instanceof StatementAssignment assign && Operators.ADDRESS_OF.equals(assign.getOperator())) {
               if(assign.getrValue2() instanceof VariableRef vr) {
                  addressTaken.add(vr);
               }
            }
         }
      }
      return addressTaken;
   }

   private static Map<VariableRef, Integer> collectDefCounts(Graph graph) {
      Map<VariableRef, Integer> defCounts = new HashMap<>();
      for(Graph.Block block : graph.getAllBlocks()) {
         for(Statement stmt : block.getStatements()) {
            if(stmt instanceof StatementLValue lValueStmt) {
               if(lValueStmt.getlValue() instanceof VariableRef vr) {
                  defCounts.merge(vr, 1, Integer::sum);
               }
            } else if(stmt instanceof StatementPhiBlock phi) {
               for(StatementPhiBlock.PhiVariable pv : phi.getPhiVariables()) {
                  defCounts.merge(pv.getVariable(), 1, Integer::sum);
               }
            }
         }
      }
      return defCounts;
   }

   private static boolean isEligibleSsa(VariableRef vr, ProgramScope scope, Set<VariableRef> addressTaken, Map<VariableRef, Integer> defCounts) {
      if(vr == null) return false;
      Variable var = scope.getVariable(vr);
      if(var == null || var.isVolatile() || var.isKindLoadStore() || var.isKindConstant()) return false;
      if(addressTaken.contains(vr)) return false;
      return defCounts.getOrDefault(vr, 0) == 1;
   }

   public static Interval typeBounds(SymbolType type) {
      if(type instanceof SymbolTypeIntegerFixed fixed) {
         return new Interval(fixed.getMinValue(), fixed.getMaxValue());
      }
      if(SymbolType.BOOLEAN.equals(type)) {
         return new Interval(0L, 1L);
      }
      return null;
   }

   public static Interval evalRValue(RValue val, Map<VariableRef, Interval> env, ProgramScope scope) {
      if(val == null) return null;
      if(val instanceof ConstantInteger ci) {
         long v = ci.getInteger();
         return new Interval(v, v);
      }
      if(val instanceof ConstantBool cb) {
         long v = cb.getBool() ? 1L : 0L;
         return new Interval(v, v);
      }
      if(val instanceof ConstantRef cr) {
         Variable cVar = scope.getConstant(cr);
         if(cVar != null && cVar.getInitValue() != null) {
            try {
               ConstantLiteral lit = cVar.getInitValue().calculateLiteral(scope);
               if(lit instanceof ConstantInteger ci) {
                  long v = ci.getInteger();
                  return new Interval(v, v);
               }
            } catch(ConstantNotLiteral ignored) {
            }
         }
         return typeBounds(SymbolTypeInference.inferType(scope, cr));
      }
      if(val instanceof ConstantValue cv) {
         try {
            ConstantLiteral lit = cv.calculateLiteral(scope);
            if(lit instanceof ConstantInteger ci) {
               long v = ci.getInteger();
               return new Interval(v, v);
            }
         } catch(ConstantNotLiteral ignored) {
         }
         return typeBounds(SymbolTypeInference.inferType(scope, cv));
      }
      if(val instanceof CastValue cast) {
         Interval targetBounds = typeBounds(cast.getToType());
         if(targetBounds == null) return null;
         Interval inner = evalRValue(cast.getValue(), env, scope);
         if(inner != null && inner.fitsIn(targetBounds.min, targetBounds.max)) {
            return inner;
         }
         return targetBounds;
      }
      if(val instanceof VariableRef vr) {
         Interval tracked = env.get(vr);
         if(tracked != null) return tracked;
         return typeBounds(SymbolTypeInference.inferType(scope, vr));
      }
      return typeBounds(SymbolTypeInference.inferType(scope, val));
   }

   private static Interval evalAssignment(StatementAssignment assign, SymbolType dstType, Map<VariableRef, Interval> env, ProgramScope scope) {
      Interval bounds = typeBounds(dstType);
      if(bounds == null) return null;

      Operator op = assign.getOperator();
      if(op == null) {
         Interval rhs = evalRValue(assign.getrValue2(), env, scope);
         if(rhs != null && rhs.fitsIn(bounds.min, bounds.max)) {
            return rhs;
         }
         return bounds;
      }

      if(assign.getrValue1() == null) {
         if(op instanceof OperatorCast castOp) {
            Interval castBounds = typeBounds(castOp.getToType());
            if(castBounds == null) return bounds;
            Interval inner = evalRValue(assign.getrValue2(), env, scope);
            Interval effective = (inner != null && inner.fitsIn(castBounds.min, castBounds.max)) ? inner : castBounds;
            return effective.fitsIn(bounds.min, bounds.max) ? effective : bounds;
         }
         if(Operators.NEG.equals(op)) {
            Interval a = evalRValue(assign.getrValue2(), env, scope);
            if(a != null && a.min > Long.MIN_VALUE) {
               Interval raw = new Interval(-a.max, -a.min);
               if(raw.fitsIn(bounds.min, bounds.max)) return raw;
            }
         }
         return bounds;
      }

      Interval a = evalRValue(assign.getrValue1(), env, scope);
      Interval b = evalRValue(assign.getrValue2(), env, scope);
      if(a == null || b == null) return bounds;

      Interval raw = null;
      if(Operators.PLUS.equals(op)) {
         try {
            long lo = Math.addExact(a.min, b.min);
            long hi = Math.addExact(a.max, b.max);
            raw = new Interval(lo, hi);
         } catch(ArithmeticException ignored) {
         }
      } else if(Operators.MINUS.equals(op)) {
         try {
            long lo = Math.subtractExact(a.min, b.max);
            long hi = Math.subtractExact(a.max, b.min);
            raw = new Interval(lo, hi);
         } catch(ArithmeticException ignored) {
         }
      } else if(Operators.MULTIPLY.equals(op)) {
         try {
            long p1 = Math.multiplyExact(a.min, b.min);
            long p2 = Math.multiplyExact(a.min, b.max);
            long p3 = Math.multiplyExact(a.max, b.min);
            long p4 = Math.multiplyExact(a.max, b.max);
            long lo = Math.min(Math.min(p1, p2), Math.min(p3, p4));
            long hi = Math.max(Math.max(p1, p2), Math.max(p3, p4));
            raw = new Interval(lo, hi);
         } catch(ArithmeticException ignored) {
         }
      } else if(Operators.BOOL_AND.equals(op)) {
         if(b.isConstant() && b.min >= 0) {
            long c = b.min;
            long hi = (a.min >= 0) ? Math.min(a.max, c) : c;
            raw = new Interval(0L, hi);
         } else if(a.isConstant() && a.min >= 0) {
            long c = a.min;
            long hi = (b.min >= 0) ? Math.min(b.max, c) : c;
            raw = new Interval(0L, hi);
         } else if(a.min >= 0 && b.min >= 0) {
            raw = new Interval(0L, Math.min(a.max, b.max));
         }
      } else if(Operators.BOOL_OR.equals(op)) {
         if(a.min >= 0 && b.min >= 0) {
            long hi = bitMaskUpperBound(a.max | b.max);
            if(hi >= 0) {
               raw = new Interval(Math.max(a.min, b.min), hi);
            }
         }
      } else if(Operators.BOOL_XOR.equals(op)) {
         if(a.min >= 0 && b.min >= 0) {
            long hi = bitMaskUpperBound(a.max | b.max);
            if(hi >= 0) {
               raw = new Interval(0L, hi);
            }
         }
      } else if(Operators.SHIFT_RIGHT.equals(op)) {
         if(b.isConstant() && b.min >= 0 && b.min <= 62) {
            int sh = (int) b.min;
            if(a.min >= 0 || (dstType instanceof SymbolTypeIntegerFixed fixed && fixed.isSigned())) {
               raw = new Interval(a.min >> sh, a.max >> sh);
            }
         }
      } else if(Operators.SHIFT_LEFT.equals(op)) {
         if(b.isConstant() && b.min >= 0 && b.min <= 61) {
            int sh = (int) b.min;
            long lo = a.min << sh;
            long hi = a.max << sh;
            if((lo >> sh) == a.min && (hi >> sh) == a.max) {
               raw = new Interval(lo, hi);
            }
         }
      } else if(Operators.DIVIDE.equals(op)) {
         if(b.min >= 1) {
            long p1 = a.min / b.min;
            long p2 = a.min / b.max;
            long p3 = a.max / b.min;
            long p4 = a.max / b.max;
            raw = new Interval(Math.min(Math.min(p1, p2), Math.min(p3, p4)), Math.max(Math.max(p1, p2), Math.max(p3, p4)));
         }
      } else if(Operators.MODULO.equals(op)) {
         if(a.min >= 0 && b.min >= 1) {
            raw = new Interval(0L, Math.min(a.max, b.max - 1));
         }
      }

      if(raw != null && raw.fitsIn(bounds.min, bounds.max)) {
         return raw;
      }
      return bounds;
   }

   private static long bitMaskUpperBound(long maxOr) {
      if(maxOr == 0L) return 0L;
      if(maxOr < 0L) return -1L;
      int bits = 64 - Long.numberOfLeadingZeros(maxOr);
      if(bits >= 63) return -1L;
      return (1L << bits) - 1L;
   }

   private static boolean isNarrowableBinaryOp(Operator op) {
      return Operators.PLUS.equals(op)
            || Operators.MINUS.equals(op)
            || Operators.BOOL_AND.equals(op)
            || Operators.BOOL_OR.equals(op)
            || Operators.BOOL_XOR.equals(op)
            || Operators.SHIFT_RIGHT.equals(op);
   }

   private static SymbolTypeIntegerFixed selectNarrowType(Operator op, Interval i1, Interval i2, Interval res) {
      if(res.fitsIn(0L, 255L) && i1.fitsIn(0L, 255L)) {
         boolean rhsOk = Operators.SHIFT_RIGHT.equals(op) ? i2.fitsIn(0L, 7L) : i2.fitsIn(0L, 255L);
         if(rhsOk) {
            return SymbolType.BYTE;
         }
      }
      if(res.fitsIn(-128L, 127L) && i1.fitsIn(-128L, 127L)) {
         boolean rhsOk = Operators.SHIFT_RIGHT.equals(op) ? i2.fitsIn(0L, 7L) : i2.fitsIn(-128L, 127L);
         if(rhsOk) {
            return SymbolType.SBYTE;
         }
      }
      return null;
   }

   private RValue unwrapOrCastNarrow(
         RValue val,
         SymbolTypeIntegerFixed targetType,
         VariableReferenceInfos refInfos,
         StmtLookup stmtLookup,
         Map<VariableRef, VariableRef> narrowedToVar,
         Map<VariableRef, SymbolTypeIntegerFixed> narrowedToType) {
      if(val instanceof ConstantInteger ci) {
         return new ConstantInteger(targetType.normalize(ci.getInteger()), targetType);
      }
      if(val instanceof CastValue cast) {
         SymbolType innerType = SymbolTypeInference.inferType(getProgramScope(), cast.getValue());
         if(targetType.equals(innerType)) {
            return cast.getValue();
         }
      }
      if(val instanceof VariableRef vr) {
         VariableRef alreadyNarrow = narrowedToVar.get(vr);
         if(alreadyNarrow != null && targetType.equals(narrowedToType.get(vr))) {
            return alreadyNarrow;
         }
         SymbolType vrType = SymbolTypeInference.inferType(getProgramScope(), vr);
         if(targetType.equals(vrType)) {
            return vr;
         }
         Integer defIdx = refInfos.getVarDefineStatement(vr);
         Statement def = (defIdx != null) ? stmtLookup.get(defIdx) : null;
         if(def instanceof StatementAssignment defAssign && defAssign.getOperator() == null) {
            RValue rhs = defAssign.getrValue2();
            if(rhs instanceof CastValue innerCast) {
               SymbolType innerType = SymbolTypeInference.inferType(getProgramScope(), innerCast.getValue());
               if(targetType.equals(innerType)) {
                  return innerCast.getValue();
               }
            } else if(rhs instanceof VariableRef innerVar) {
               SymbolType innerType = SymbolTypeInference.inferType(getProgramScope(), innerVar);
               if(targetType.equals(innerType)) {
                  return innerVar;
               }
            }
         }
      }
      return new CastValue(targetType, val);
   }

   private void simplifyNarrowedCasts(Map<VariableRef, VariableRef> narrowedToVar, Map<VariableRef, SymbolTypeIntegerFixed> narrowedToType) {
      ProgramValueIterator.execute(getGraph(), (programValue, currentStmt, stmtIt, currentBlock) -> {
         Value val = programValue.get();
         if(val instanceof CastValue cast && cast.getValue() instanceof VariableRef vr) {
            VariableRef narrowVar = narrowedToVar.get(vr);
            if(narrowVar != null && narrowedToType.get(vr).equals(cast.getToType())) {
               // Skip rewriting the widening assignment dst = (wideType) narrowVar itself (narrowVar isn't a key anyway)
               programValue.set(narrowVar);
            }
         }
      });
      for(Graph.Block block : getGraph().getAllBlocks()) {
         for(Statement stmt : block.getStatements()) {
            if(stmt instanceof StatementAssignment assign && assign.getrValue1() == null && assign.getOperator() instanceof OperatorCast castOp) {
               if(assign.getrValue2() instanceof VariableRef vr) {
                  VariableRef narrowVar = narrowedToVar.get(vr);
                  if(narrowVar != null && narrowedToType.get(vr).equals(castOp.getToType())) {
                     assign.setOperator(null);
                     assign.setrValue2(narrowVar);
                  }
               }
            }
         }
      }
   }

   private RValue unwrapUnsignedByteCast(RValue val, VariableReferenceInfos refInfos, StmtLookup stmtLookup) {
      if(val instanceof ConstantInteger ci) {
         long v = ci.getInteger();
         if(v >= 0 && v <= 127) return new ConstantInteger(v, SymbolType.BYTE);
         return null;
      }
      if(val instanceof CastValue cast) {
         if(SymbolType.SWORD.equals(cast.getToType())) {
            SymbolType srcType = SymbolTypeInference.inferType(getProgramScope(), cast.getValue());
            if(SymbolType.BYTE.equals(srcType)) {
               return cast.getValue();
            }
         }
         return null;
      }
      if(val instanceof VariableRef vr) {
         Integer defIdx = refInfos.getVarDefineStatement(vr);
         Statement def = (defIdx != null) ? stmtLookup.get(defIdx) : null;
         if(def instanceof StatementAssignment defAssign && defAssign.getOperator() == null) {
            return unwrapUnsignedByteCast(defAssign.getrValue2(), refInfos, stmtLookup);
         }
      }
      return null;
   }

   private int inferUnsignedByteMax(RValue val, VariableReferenceInfos refInfos, StmtLookup stmtLookup, int depth) {
      if(depth > 4 || val == null) return 255;
      if(val instanceof ConstantInteger ci) {
         long v = ci.getInteger();
         return (v >= 0 && v <= 255) ? (int) v : 255;
      }
      if(!(val instanceof VariableRef vr)) return 255;
      Variable var = getProgramScope().getVariable(vr);
      if(var == null || var.isVolatile() || !SymbolType.BYTE.equals(var.getType())) return 255;

      Integer defIdx = refInfos.getVarDefineStatement(vr);
      Statement def = (defIdx != null) ? stmtLookup.get(defIdx) : null;
      if(!(def instanceof StatementAssignment defAssign)) return 255;

      if(defAssign.getOperator() == null) {
         return inferUnsignedByteMax(defAssign.getrValue2(), refInfos, stmtLookup, depth + 1);
      }
      if(Operators.BOOL_AND.equals(defAssign.getOperator()) && defAssign.getrValue2() instanceof ConstantInteger ci) {
         long m = ci.getInteger();
         if(m >= 0 && m <= 255) return (int) m;
      }
      if(Operators.SHIFT_RIGHT.equals(defAssign.getOperator()) && defAssign.getrValue2() instanceof ConstantInteger ci) {
         long sh = ci.getInteger();
         if(sh >= 1 && sh <= 7) {
            int srcMax = inferUnsignedByteMax(defAssign.getrValue1(), refInfos, stmtLookup, depth + 1);
            return srcMax >> (int) sh;
         }
      }
      if(Operators.DIVIDE.equals(defAssign.getOperator()) && defAssign.getrValue2() instanceof ConstantInteger ci) {
         long d = ci.getInteger();
         if(d >= 2) {
            int srcMax = inferUnsignedByteMax(defAssign.getrValue1(), refInfos, stmtLookup, depth + 1);
            return (int) (srcMax / d);
         }
      }
      if(Operators.PLUS.equals(defAssign.getOperator())) {
         int m1 = inferUnsignedByteMax(defAssign.getrValue1(), refInfos, stmtLookup, depth + 1);
         int m2 = inferUnsignedByteMax(defAssign.getrValue2(), refInfos, stmtLookup, depth + 1);
         if(m1 + m2 <= 255) return m1 + m2;
      }
      return 255;
   }
}
