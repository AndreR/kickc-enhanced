package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.VariableBuilder;
import dk.camelot64.kickc.model.VariableReferenceInfos;
import dk.camelot64.kickc.model.operators.OperatorUnary;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeInference;
import dk.camelot64.kickc.model.types.SymbolTypeIntegerFixed;
import dk.camelot64.kickc.model.values.CastValue;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.model.values.ConstantLiteral;
import dk.camelot64.kickc.model.values.ConstantValue;
import dk.camelot64.kickc.model.values.RValue;
import dk.camelot64.kickc.model.values.VariableRef;

import java.util.Collection;
import java.util.ListIterator;

/**
 * GEN-02: Canonicalizes constant byte-aligned right shifts narrowed to 8 bits into direct
 * BYTE1/BYTE2/BYTE3 extractions (and high-byte single-byte shifts) in SSA IR.
 */
public class Pass2BitShiftCanonicalization extends Pass2SsaOptimization {

   public Pass2BitShiftCanonicalization(Program program) {
      super(program);
   }

   @Override
   public boolean step() {
      boolean modified = false;
      VariableReferenceInfos refInfos = getProgram().getVariableReferenceInfos();

      for(Graph.Block block : getGraph().getAllBlocks()) {
         ListIterator<Statement> it = block.getStatements().listIterator();
         while(it.hasNext()) {
            Statement stmt = it.next();
            if(!(stmt instanceof StatementAssignment assign)) continue;
            if(assign.getOperator() != null) continue;
            if(!(assign.getlValue() instanceof VariableRef dstRef)) continue;

            // Match: dst = (uint8_t) shiftTemp   (or dst = shiftTemp where dst is 1 byte)
            SymbolType dstType = SymbolTypeInference.inferType(getProgramScope(), dstRef);
            if(!(dstType instanceof SymbolTypeIntegerFixed dstInt) || dstInt.getSizeBytes() != 1) continue;

            RValue rhs = assign.getrValue2();
            VariableRef shiftVarRef = null;
            if(rhs instanceof CastValue cast && cast.getValue() instanceof VariableRef vr) {
               shiftVarRef = vr;
            } else if(rhs instanceof VariableRef vr) {
               shiftVarRef = vr;
            }
            if(shiftVarRef == null) continue;

            Collection<Integer> uses = refInfos.getVarUseStatements(shiftVarRef);
            if(uses == null || uses.size() != 1) continue;
            Integer defIdx = refInfos.getVarDefineStatement(shiftVarRef);
            if(defIdx == null) continue;
            Statement defStmt = getGraph().getStatementByIndex(defIdx);
            if(!(defStmt instanceof StatementAssignment shiftAssign)) continue;
            if(!Operators.SHIFT_RIGHT.equals(shiftAssign.getOperator())) continue;
            if(!(shiftAssign.getrValue2() instanceof ConstantValue shiftConst)) continue;

            ConstantLiteral shiftLit;
            try {
               shiftLit = shiftConst.calculateLiteral(getProgramScope());
            } catch(Exception e) {
               continue;
            }
            if(!(shiftLit instanceof ConstantInteger ci)) continue;
            long shiftAmt = ci.getValue();
            if(shiftAmt < 8) continue;

            RValue src = shiftAssign.getrValue1();
            if(!(src instanceof VariableRef srcRef)) continue;
            Variable srcVar = getProgramScope().getVariable(srcRef);
            if(srcVar != null && srcVar.isVolatile()) continue;
            Variable shiftVar = getProgramScope().getVariable(shiftVarRef);
            if(shiftVar == null || !shiftVar.isKindIntermediate()) continue;
            SymbolType srcType = SymbolTypeInference.inferType(getProgramScope(), src);
            if(!(srcType instanceof SymbolTypeIntegerFixed srcInt)) continue;
            // Only rewrite unsigned shifts (or exact byte-aligned shifts where sign bit is preserved)
            int srcBytes = srcInt.getSizeBytes();
            int q = (int) (shiftAmt / 8);
            int r = (int) (shiftAmt % 8);
            if(q >= srcBytes) continue;
            if(r != 0 && srcInt.isSigned()) continue;

            OperatorUnary byteOp = switch(q) {
               case 1 -> Operators.BYTE1;
               case 2 -> Operators.BYTE2;
               case 3 -> Operators.BYTE3;
               default -> null;
            };
            if(byteOp == null) continue;

            if(r == 0) {
               // KRIT-08: Rewrite shiftAssign IN-PLACE so we never sink reads of src across intervening writes,
               // and preserve explicit CastValue for non-BYTE targets (e.g. signed byte / SBYTE) for Pass2AssertTypeMatch!
               getLog().append("GEN-02: Canonicalizing " + shiftAssign.toString(getProgram(), false) + " -> " + byteOp.getOperator() + "(" + src + ")");
               shiftVar.setType(SymbolType.BYTE);
               shiftAssign.setrValue1(null);
               shiftAssign.setOperator(byteOp);
               shiftAssign.setrValue2(src);
               if(SymbolType.BYTE.equals(dstType)) {
                  assign.setrValue2(shiftVarRef);
               } else {
                  assign.setrValue2(new CastValue(dstType, shiftVarRef));
               }
               modified = true;
               break;
            } else if(q == srcBytes - 1) {
               // Top-byte shift: rewrite shiftAssign in-place to BYTEq(src), and shift in 8 bits
               getLog().append("GEN-02: Canonicalizing top-byte shift " + assign.toString(getProgram(), false) + " -> " + byteOp.getOperator() + "(" + src + ") >> " + r);
               shiftVar.setType(SymbolType.BYTE);
               shiftAssign.setrValue1(null);
               shiftAssign.setOperator(byteOp);
               shiftAssign.setrValue2(src);
               if(SymbolType.BYTE.equals(dstType)) {
                  assign.setrValue1(shiftVarRef);
                  assign.setOperator(Operators.SHIFT_RIGHT);
                  assign.setrValue2(new ConstantInteger((long) r, SymbolType.BYTE));
               } else {
                  // For SBYTE target, shift into a BYTE intermediate first, then cast to dstType
                  Variable shiftedTmp = VariableBuilder.createIntermediate(shiftVar.getScope(), SymbolType.BYTE, getProgram());
                  StatementAssignment shiftedAssign = new StatementAssignment(shiftedTmp.getVariableRef(), shiftVarRef, Operators.SHIFT_RIGHT, new ConstantInteger((long) r, SymbolType.BYTE), true, assign.getSource(), assign.getComments());
                  it.previous();
                  it.add(shiftedAssign);
                  it.next();
                  assign.setrValue1(null);
                  assign.setOperator(null);
                  assign.setrValue2(new CastValue(dstType, shiftedTmp.getRef()));
               }
               modified = true;
               break;
            }
         }
      }
      if(modified) {
         new PassNStatementIndices(getProgram()).step();
         getProgram().clearVariableReferenceInfos();
         getProgram().clearStatementInfos();
      }
      return modified;
   }
}
