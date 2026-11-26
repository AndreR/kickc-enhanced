package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.Comment;
import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.ConstantNotLiteral;
import dk.camelot64.kickc.model.ControlFlowBlock;
import dk.camelot64.kickc.model.ControlFlowGraph;
import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.ProcedureCompilation;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.VariableBuilder;
import dk.camelot64.kickc.model.iterator.ProgramValue;
import dk.camelot64.kickc.model.iterator.ProgramValueIterator;
import dk.camelot64.kickc.model.operators.Operator;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.statements.StatementConditionalJump;
import dk.camelot64.kickc.model.statements.StatementLValue;
import dk.camelot64.kickc.model.statements.StatementSource;
import dk.camelot64.kickc.model.symbols.Label;
import dk.camelot64.kickc.model.symbols.ProgramScope;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypePointer;
import dk.camelot64.kickc.model.values.CastValue;
import dk.camelot64.kickc.model.values.ConstantBool;
import dk.camelot64.kickc.model.values.ConstantCastValue;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.model.values.ConstantLiteral;
import dk.camelot64.kickc.model.values.ConstantSymbolPointer;
import dk.camelot64.kickc.model.values.ConstantValue;
import dk.camelot64.kickc.model.values.LabelRef;
import dk.camelot64.kickc.model.values.PointerDereferenceSimple;
import dk.camelot64.kickc.model.values.RValue;
import dk.camelot64.kickc.model.values.Value;
import dk.camelot64.kickc.model.values.VariableRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

/**
 * Pass 1 lowering for bit-addressable MMIO boolean variables declared with __bit(addr, bitIndex).
 *
 * Lowers:
 * - Constant writes (`bitVar = true` / `false`): volatile read-modify-write `*ptr |= mask` or `*ptr &= ~mask`
 * - Dynamic writes (`bitVar = cond`): conditional branch between set and clear RMW blocks
 * - Reads / conditions (`if (bitVar)`, `!bitVar`, `b = bitVar`): volatile `((*ptr & mask) != 0)` so
 *   Pass 5 bit-test optimization folds bit 7 / bit 6 checks into `bit addr; bmi/bpl/bvs/bvc`.
 *
 * Removes lowered synthetic __bit variables from symbol scopes so they consume 0 bytes of RAM/ZP.
 */
public class Pass1BitAddressLowering extends Pass1Base {

   public Pass1BitAddressLowering(Program program) {
      super(program);
   }

   @Override
   public boolean step() {
      ProgramScope scope = getProgramScope();
      Map<VariableRef, Variable> bitVars = collectBitAddressableVars(scope);
      if(bitVars.isEmpty()) {
         return false;
      }

      boolean modified = false;
      for(ProcedureCompilation pc : getProgram().getProcedureCompilations()) {
         ControlFlowGraph procGraph = pc.getGraph();
         if(procGraph == null) continue;
         boolean procChanged;
         do {
            procChanged = lowerProcedureGraph(procGraph, bitVars, scope);
            if(procChanged) {
               modified = true;
            }
         } while(procChanged);
      }

      // Remove lowered __bit variables that have no remaining references anywhere in the program
      Set<VariableRef> stillReferenced = collectReferencedVars(getGraph());
      for(Map.Entry<VariableRef, Variable> entry : bitVars.entrySet()) {
         if(!stillReferenced.contains(entry.getKey())) {
            Variable bitVar = entry.getValue();
            if(bitVar.getScope() != null) {
               bitVar.getScope().remove(bitVar);
            }
            modified = true;
         }
      }

      if(modified) {
         getProgram().clearVariableReferenceInfos();
         getProgram().clearStatementInfos();
         getProgram().clearControlFlowBlockSuccessorClosure();
      }
      return modified;
   }

   private boolean lowerProcedureGraph(ControlFlowGraph procGraph, Map<VariableRef, Variable> bitVars, ProgramScope scope) {
      for(Graph.Block block : new ArrayList<>(procGraph.getAllBlocks())) {
         Scope blockScope = scope.getScope(block.getScope());
         List<Statement> stmts = block.getStatements();
         ListIterator<Statement> stmtIt = stmts.listIterator();
         while(stmtIt.hasNext()) {
            int stmtIdx = stmtIt.nextIndex();
            Statement stmt = stmtIt.next();

            // Guard against taking the address of a bit-addressable variable
            if(stmt instanceof StatementAssignment assign && Operators.ADDRESS_OF.equals(assign.getOperator())) {
               if(assign.getrValue2() instanceof VariableRef vr && bitVars.containsKey(vr)) {
                  throw new CompileError("Cannot take address of __bit variable " + bitVars.get(vr).getLocalName(), stmt);
               }
            }

            // 1. Direct conditional jumps testing a __bit variable
            if(stmt instanceof StatementConditionalJump condJmp) {
               if(lowerConditionalJump(condJmp, bitVars, blockScope, stmtIt)) {
                  return true;
               }
            }

            // 2. Replace any RValue references to __bit variables inside this statement
            if(lowerStatementRValues(stmt, bitVars, blockScope, stmtIt)) {
               return true;
            }

            // 3. Lower writes where lValue is a __bit variable
            if(stmt instanceof StatementLValue lValueStmt && lValueStmt.getlValue() instanceof VariableRef dstRef) {
               Variable bitVar = bitVars.get(dstRef);
               if(bitVar != null) {
                  lowerBitWrite(procGraph, block, blockScope, stmtIdx, stmtIt, lValueStmt, bitVar, scope);
                  return true;
               }
            }
         }
      }
      return false;
   }

   private boolean lowerConditionalJump(
         StatementConditionalJump condJmp,
         Map<VariableRef, Variable> bitVars,
         Scope blockScope,
         ListIterator<Statement> stmtIt) {
      Operator op = condJmp.getOperator();
      VariableRef bitRef = null;
      boolean jumpWhenSet = true;

      if(op == null && condJmp.getrValue1() == null && condJmp.getrValue2() instanceof VariableRef vr && bitVars.containsKey(vr)) {
         bitRef = vr;
         jumpWhenSet = true;
      } else if((Operators.EQ.equals(op) || Operators.NEQ.equals(op))) {
         if(condJmp.getrValue1() instanceof VariableRef vr && bitVars.containsKey(vr)) {
            Boolean c = tryEvalConstantBool(condJmp.getrValue2(), getProgramScope());
            if(c != null) {
               bitRef = vr;
               jumpWhenSet = Operators.EQ.equals(op) ? c : !c;
            }
         } else if(condJmp.getrValue2() instanceof VariableRef vr && bitVars.containsKey(vr)) {
            Boolean c = tryEvalConstantBool(condJmp.getrValue1(), getProgramScope());
            if(c != null) {
               bitRef = vr;
               jumpWhenSet = Operators.EQ.equals(op) ? c : !c;
            }
         }
      }

      if(bitRef == null) return false;

      Variable bitVar = bitVars.get(bitRef);
      ConstantValue ptr = createVolatileBytePointer(bitVar, condJmp);
      ConstantInteger maskConst = createBitMaskConstant(bitVar, false, condJmp);

      Variable rawVar = VariableBuilder.createIntermediate(blockScope, SymbolType.BYTE, getProgram());
      Variable maskVar = VariableBuilder.createIntermediate(blockScope, SymbolType.BYTE, getProgram());

      stmtIt.previous();
      stmtIt.add(new StatementAssignment(
            rawVar.getVariableRef(), null, null,
            new PointerDereferenceSimple(ptr), true, condJmp.getSource(), Comment.NO_COMMENTS));
      stmtIt.add(new StatementAssignment(
            maskVar.getVariableRef(), rawVar.getVariableRef(), Operators.BOOL_AND,
            maskConst, true, condJmp.getSource(), Comment.NO_COMMENTS));
      stmtIt.next();

      condJmp.setrValue1(maskVar.getVariableRef());
      condJmp.setOperator(jumpWhenSet ? Operators.NEQ : Operators.EQ);
      condJmp.setrValue2(new ConstantInteger(0L, SymbolType.BYTE));
      return true;
   }

   private boolean lowerStatementRValues(
         Statement stmt,
         Map<VariableRef, Variable> bitVars,
         Scope blockScope,
         ListIterator<Statement> stmtIt) {
      List<ProgramValue> matches = new ArrayList<>();
      ProgramValueIterator.execute(stmt, (programValue, currentStmt, it, currentBlock) -> {
         if(programValue instanceof ProgramValue.ProgramValueLValue
               || programValue instanceof ProgramValue.ProgramValueLValueIntermediateVariable) {
            return;
         }
         Value val = programValue.get();
         if(val instanceof ConstantSymbolPointer csp && csp.getToSymbol() instanceof VariableRef vr && bitVars.containsKey(vr)) {
            throw new CompileError("Cannot take address of __bit variable " + bitVars.get(vr).getLocalName(), stmt);
         }
         if(val instanceof VariableRef vr && bitVars.containsKey(vr)) {
            matches.add(programValue);
         }
      }, null, null);

      if(matches.isEmpty()) return false;

      // Replace the first RValue match and let the loop continue cleanly
      ProgramValue targetPv = matches.get(0);
      VariableRef bitRef = (VariableRef) targetPv.get();
      Variable bitVar = bitVars.get(bitRef);
      ConstantValue ptr = createVolatileBytePointer(bitVar, stmt);
      ConstantInteger maskConst = createBitMaskConstant(bitVar, false, stmt);

      Variable rawVar = VariableBuilder.createIntermediate(blockScope, SymbolType.BYTE, getProgram());
      Variable maskVar = VariableBuilder.createIntermediate(blockScope, SymbolType.BYTE, getProgram());
      Variable boolVar = VariableBuilder.createIntermediate(blockScope, SymbolType.BOOLEAN, getProgram());

      stmtIt.previous();
      stmtIt.add(new StatementAssignment(
            rawVar.getVariableRef(), null, null,
            new PointerDereferenceSimple(ptr), true, stmt.getSource(), Comment.NO_COMMENTS));
      stmtIt.add(new StatementAssignment(
            maskVar.getVariableRef(), rawVar.getVariableRef(), Operators.BOOL_AND,
            maskConst, true, stmt.getSource(), Comment.NO_COMMENTS));
      stmtIt.add(new StatementAssignment(
            boolVar.getVariableRef(), maskVar.getVariableRef(), Operators.NEQ,
            new ConstantInteger(0L, SymbolType.BYTE), true, stmt.getSource(), Comment.NO_COMMENTS));
      stmtIt.next();

      targetPv.set(boolVar.getVariableRef());
      return true;
   }

   private void lowerBitWrite(
         ControlFlowGraph procGraph,
         Graph.Block block,
         Scope blockScope,
         int stmtIdx,
         ListIterator<Statement> stmtIt,
         StatementLValue lValueStmt,
         Variable bitVar,
         ProgramScope scope) {
      ConstantValue ptr = createVolatileBytePointer(bitVar, lValueStmt);
      ConstantInteger maskConst = createBitMaskConstant(bitVar, false, lValueStmt);
      ConstantInteger invMaskConst = createBitMaskConstant(bitVar, true, lValueStmt);
      StatementSource src = lValueStmt.getSource();
      List<Comment> comments = lValueStmt.getComments();

      if(lValueStmt instanceof StatementAssignment assign && assign.getOperator() == null && assign.getrValue1() == null) {
         Boolean constBool = tryEvalConstantBool(assign.getrValue2(), scope);
         if(constBool != null) {
            stmtIt.remove();
            emitBitRmw(stmtIt, blockScope, ptr, constBool ? Operators.BOOL_OR : Operators.BOOL_AND,
                  constBool ? maskConst : invMaskConst, src, comments);
            getLog().append("Lowered constant __bit write on " + bitVar.getLocalName() + " = " + constBool);
            return;
         }
      }

      // Dynamic write: evaluate RHS into intermediate boolean condVar, then branch to set/clear RMW blocks
      Variable condVar = VariableBuilder.createIntermediate(blockScope, SymbolType.BOOLEAN, getProgram());
      lValueStmt.setlValue(condVar.getVariableRef());

      List<Statement> stmts = block.getStatements();
      List<Statement> tailStmts = new ArrayList<>();
      for(int k = stmtIdx + 1; k < stmts.size(); k++) {
         tailStmts.add(stmts.get(k));
      }
      while(stmts.size() > stmtIdx + 1) {
         stmts.remove(stmts.size() - 1);
      }

      Label setLabel = blockScope.addLabelIntermediate();
      Label clearLabel = blockScope.addLabelIntermediate();
      Label contLabel = blockScope.addLabelIntermediate();

      ControlFlowBlock setBlock = new ControlFlowBlock(setLabel.getRef(), block.getScope());
      ControlFlowBlock clearBlock = new ControlFlowBlock(clearLabel.getRef(), block.getScope());
      ControlFlowBlock contBlock = new ControlFlowBlock(contLabel.getRef(), block.getScope());

      emitBitRmw(setBlock.getStatements().listIterator(), blockScope, ptr, Operators.BOOL_OR, maskConst, src, comments);
      setBlock.setDefaultSuccessor(contLabel.getRef());

      emitBitRmw(clearBlock.getStatements().listIterator(), blockScope, ptr, Operators.BOOL_AND, invMaskConst, src, comments);
      clearBlock.setDefaultSuccessor(contLabel.getRef());

      contBlock.getStatements().addAll(tailStmts);
      contBlock.setDefaultSuccessor(block.getDefaultSuccessor());
      contBlock.setConditionalSuccessor(block.getConditionalSuccessor());
      contBlock.setCallSuccessor(block.getCallSuccessor());

      block.addStatement(new StatementConditionalJump(condVar.getVariableRef(), setLabel.getRef(), src, Comment.NO_COMMENTS));
      block.setConditionalSuccessor(setLabel.getRef());
      block.setDefaultSuccessor(clearLabel.getRef());
      block.setCallSuccessor(null);

      procGraph.addBlock(setBlock);
      procGraph.addBlock(clearBlock);
      procGraph.addBlock(contBlock);
      getLog().append("Lowered dynamic __bit write on " + bitVar.getLocalName());
   }

   private void emitBitRmw(
         ListIterator<Statement> it,
         Scope blockScope,
         ConstantValue ptr,
         Operator rmwOp,
         ConstantInteger maskConst,
         StatementSource src,
         List<Comment> comments) {
      Variable tmpRead = VariableBuilder.createIntermediate(blockScope, SymbolType.BYTE, getProgram());
      Variable tmpWrite = VariableBuilder.createIntermediate(blockScope, SymbolType.BYTE, getProgram());
      it.add(new StatementAssignment(
            tmpRead.getVariableRef(), null, null,
            new PointerDereferenceSimple(ptr), true, src, Comment.NO_COMMENTS));
      it.add(new StatementAssignment(
            tmpWrite.getVariableRef(), tmpRead.getVariableRef(), rmwOp,
            maskConst, true, src, Comment.NO_COMMENTS));
      it.add(new StatementAssignment(
            new PointerDereferenceSimple(ptr), null, null,
            tmpWrite.getVariableRef(), false, src, comments));
   }

   private static ConstantValue createVolatileBytePointer(Variable bitVar, Statement contextStmt) {
      ConstantValue addr = bitVar.getBitAddress();
      if(addr == null) {
         throw new CompileError("Missing bit address on __bit variable " + bitVar.getLocalName(), contextStmt);
      }
      SymbolTypePointer ptrType = new SymbolTypePointer(SymbolType.BYTE.getQualified(true, false));
      return new ConstantCastValue(ptrType, addr);
   }

   private static ConstantInteger createBitMaskConstant(Variable bitVar, boolean invert, Statement contextStmt) {
      Integer bitIdx = bitVar.getBitIndex();
      if(bitIdx == null || bitIdx < 0 || bitIdx > 7) {
         throw new CompileError("Invalid __bit index (must be 0..7) on " + bitVar.getLocalName() + ": " + bitIdx, contextStmt);
      }
      long mask = 1L << bitIdx;
      if(invert) {
         mask = (~mask) & 0xFFL;
      }
      return new ConstantInteger(mask, SymbolType.BYTE);
   }

   private static Boolean tryEvalConstantBool(RValue val, ProgramScope scope) {
      if(val instanceof CastValue cast) {
         return tryEvalConstantBool(cast.getValue(), scope);
      }
      if(val instanceof ConstantBool cb) {
         return cb.getBool();
      }
      if(val instanceof ConstantInteger ci) {
         return ci.getInteger() != 0L;
      }
      if(val instanceof ConstantValue cv) {
         try {
            ConstantLiteral lit = cv.calculateLiteral(scope);
            if(lit instanceof ConstantBool cb) return cb.getBool();
            if(lit instanceof ConstantInteger ci) return ci.getInteger() != 0L;
         } catch(ConstantNotLiteral ignored) {
         }
      }
      return null;
   }

   private static Map<VariableRef, Variable> collectBitAddressableVars(ProgramScope scope) {
      Map<VariableRef, Variable> result = new HashMap<>();
      for(Variable var : scope.getAllVars(true)) {
         if(var.isBitAddressable()) {
            result.put(var.getVariableRef(), var);
         }
      }
      return result;
   }

   private static Set<VariableRef> collectReferencedVars(Graph graph) {
      Set<VariableRef> used = new HashSet<>();
      ProgramValueIterator.execute(graph, (programValue, currentStmt, stmtIt, currentBlock) -> {
         Value val = programValue.get();
         if(val instanceof VariableRef vr) {
            used.add(vr);
         }
      });
      return used;
   }
}
