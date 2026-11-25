package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.VariableBuilder;
import dk.camelot64.kickc.model.VariableReferenceInfos;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeInference;
import dk.camelot64.kickc.model.values.*;

import java.util.ListIterator;

/**
 * OPT-05: 8-Bit Range Narrowing for 16-bit Signed Differences.
 * When (int)u1 - (int)u2 has both unsigned 8-bit operands statically bounded to [0, 127],
 * the difference is guaranteed to fit in int8_t ([-127, +127]).
 * Narrows the subtraction to 8-bit SBYTE and sign-extends the result to SWORD.
 */
public class Pass2RangeNarrowing extends Pass2SsaOptimization {

   public Pass2RangeNarrowing(Program program) {
      super(program);
   }

   @Override
   public boolean step() {
      boolean modified = false;
      VariableReferenceInfos refInfos = getProgram().getVariableReferenceInfos();

      for(Graph.Block block : getGraph().getAllBlocks()) {
         Scope blockScope = getProgramScope().getScope(block.getScope());
         ListIterator<Statement> it = block.getStatements().listIterator();
         while(it.hasNext()) {
            Statement stmt = it.next();
            if(!(stmt instanceof StatementAssignment assign)) continue;
            if(!Operators.MINUS.equals(assign.getOperator())) continue;
            if(!(assign.getlValue() instanceof VariableRef dstRef)) continue;

            SymbolType dstType = SymbolTypeInference.inferType(getProgramScope(), dstRef);
            if(!SymbolType.SWORD.equals(dstType)) continue;

            RValue u1 = unwrapUnsignedByteCast(assign.getrValue1(), refInfos);
            RValue u2 = unwrapUnsignedByteCast(assign.getrValue2(), refInfos);
            if(u1 == null || u2 == null) continue;

            int max1 = inferUnsignedByteMax(u1, refInfos, 0);
            int max2 = inferUnsignedByteMax(u2, refInfos, 0);
            if(max1 > 127 || max2 > 127) continue;

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
            getLog().append("OPT-05: Narrowed 16-bit signed difference to 8-bit SBYTE: " + narrowAssign.toString(getProgram(), false));
            modified = true;
         }
      }
      return modified;
   }

   private RValue unwrapUnsignedByteCast(RValue val, VariableReferenceInfos refInfos) {
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
         Statement def = (defIdx != null) ? getGraph().getStatementByIndex(defIdx) : null;
         if(def instanceof StatementAssignment defAssign && defAssign.getOperator() == null) {
            return unwrapUnsignedByteCast(defAssign.getrValue2(), refInfos);
         }
      }
      return null;
   }

   private int inferUnsignedByteMax(RValue val, VariableReferenceInfos refInfos, int depth) {
      if(depth > 4 || val == null) return 255;
      if(val instanceof ConstantInteger ci) {
         long v = ci.getInteger();
         return (v >= 0 && v <= 255) ? (int) v : 255;
      }
      if(!(val instanceof VariableRef vr)) return 255;
      Variable var = getProgramScope().getVariable(vr);
      if(var == null || var.isVolatile() || !SymbolType.BYTE.equals(var.getType())) return 255;

      Integer defIdx = refInfos.getVarDefineStatement(vr);
      Statement def = (defIdx != null) ? getGraph().getStatementByIndex(defIdx) : null;
      if(!(def instanceof StatementAssignment defAssign)) return 255;

      if(defAssign.getOperator() == null) {
         return inferUnsignedByteMax(defAssign.getrValue2(), refInfos, depth + 1);
      }
      if(Operators.BOOL_AND.equals(defAssign.getOperator()) && defAssign.getrValue2() instanceof ConstantInteger ci) {
         long m = ci.getInteger();
         if(m >= 0 && m <= 255) return (int) m;
      }
      if(Operators.SHIFT_RIGHT.equals(defAssign.getOperator()) && defAssign.getrValue2() instanceof ConstantInteger ci) {
         long sh = ci.getInteger();
         if(sh >= 1 && sh <= 7) {
            int srcMax = inferUnsignedByteMax(defAssign.getrValue1(), refInfos, depth + 1);
            return srcMax >> (int) sh;
         }
      }
      if(Operators.DIVIDE.equals(defAssign.getOperator()) && defAssign.getrValue2() instanceof ConstantInteger ci) {
         long d = ci.getInteger();
         if(d >= 2) {
            int srcMax = inferUnsignedByteMax(defAssign.getrValue1(), refInfos, depth + 1);
            return (int) (srcMax / d);
         }
      }
      if(Operators.PLUS.equals(defAssign.getOperator())) {
         int m1 = inferUnsignedByteMax(defAssign.getrValue1(), refInfos, depth + 1);
         int m2 = inferUnsignedByteMax(defAssign.getrValue2(), refInfos, depth + 1);
         if(m1 + m2 <= 255) return m1 + m2;
      }
      return 255;
   }
}
