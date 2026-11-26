package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.iterator.ProgramValue;
import dk.camelot64.kickc.model.iterator.ProgramValueIterator;
import dk.camelot64.kickc.model.operators.Operator;
import dk.camelot64.kickc.model.operators.OperatorCast;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.*;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.symbols.ProgramScope;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeInference;
import dk.camelot64.kickc.model.values.*;

import dk.camelot64.kickc.model.InternalError;
import java.util.*;

/**
 * Compiler Pass identifying R-values that are duplicates of each other - and consolidates them to a single RValue.
 */
public class Pass2DuplicateRValueIdentification extends Pass2SsaOptimization {

   public Pass2DuplicateRValueIdentification(Program program) {
      super(program);
   }

   private static record RValueKey(RValue rValue1, Operator operator, RValue rValue2) {}

   /** Per-procedure CFG cache built lazily in O(B_proc) to avoid O(B_total) getGraph().getPredecessors() scans. */
   private static final class ProcCfgCache {
      final List<Graph.Block> blocks;
      private Map<LabelRef, Graph.Block> blockMap;
      private Map<LabelRef, List<LabelRef>> predMap;
      private Set<LabelRef> globalClobberBlocks;
      private Map<ScopeRef, Set<LabelRef>> globalClobberBlocksByScope;
      private Map<VariableRef, Set<LabelRef>> varWriteBlocks;

      ProcCfgCache(List<Graph.Block> blocks) {
         this.blocks = blocks;
      }

      private void ensureBuilt() {
         if(blockMap != null) return;
         blockMap = new HashMap<>(blocks.size() * 2);
         predMap = new HashMap<>(blocks.size() * 2);
         for(Graph.Block b : blocks) {
            blockMap.put(b.getLabel(), b);
         }
         for(Graph.Block b : blocks) {
            for(LabelRef succ : b.getSuccessors()) {
               if(succ != null && blockMap.containsKey(succ)) {
                  predMap.computeIfAbsent(succ, k -> new ArrayList<>()).add(b.getLabel());
               }
            }
         }
      }

      private void ensureWriteInfoBuilt() {
         if(globalClobberBlocks != null) return;
         globalClobberBlocks = new HashSet<>();
         globalClobberBlocksByScope = new HashMap<>();
         varWriteBlocks = new HashMap<>();
         for(Graph.Block b : blocks) {
            LabelRef lbl = b.getLabel();
            ScopeRef bScope = b.getScope();
            for(Statement s : b.getStatements()) {
               if(s instanceof StatementAssignment sa) {
                  LValue lVal = sa.getlValue();
                  if(lVal instanceof VariableRef vr) {
                     varWriteBlocks.computeIfAbsent(vr, k -> new HashSet<>()).add(lbl);
                  } else if(lVal instanceof PointerDereference) {
                     if(globalClobberBlocks.add(lbl)) {
                        globalClobberBlocksByScope.computeIfAbsent(bScope, k -> new HashSet<>()).add(lbl);
                     }
                  }
               } else if(s instanceof StatementCall || s instanceof StatementCallExecute || s instanceof StatementCalling || s instanceof StatementCallPointer
                     || s instanceof StatementAsm || s instanceof StatementKickAsm) {
                  if(globalClobberBlocks.add(lbl)) {
                     globalClobberBlocksByScope.computeIfAbsent(bScope, k -> new HashSet<>()).add(lbl);
                  }
               }
            }
         }
      }

      Graph.Block getBlock(LabelRef label) {
         ensureBuilt();
         return blockMap.get(label);
      }

      List<LabelRef> getPredecessors(LabelRef label) {
         ensureBuilt();
         return predMap.getOrDefault(label, Collections.emptyList());
      }

      boolean hasOtherWriteCandidate(ScopeRef scope, LabelRef firstLabel, LabelRef secondLabel, Set<VariableRef> operands) {
         ensureWriteInfoBuilt();
         Set<LabelRef> scopeClobbers = globalClobberBlocksByScope.get(scope);
         if(scopeClobbers != null && !scopeClobbers.isEmpty()) {
            if(scopeClobbers.size() > 2) {
               return true;
            }
            for(LabelRef lbl : scopeClobbers) {
               if(!lbl.equals(firstLabel) && !lbl.equals(secondLabel)) {
                  return true;
               }
            }
         }
         for(VariableRef vr : operands) {
            Set<LabelRef> vrBlocks = varWriteBlocks.get(vr);
            if(vrBlocks != null) {
               for(LabelRef lbl : vrBlocks) {
                  if(!lbl.equals(firstLabel) && !lbl.equals(secondLabel)) {
                     Graph.Block b = getBlock(lbl);
                     if(b != null && scope.equals(b.getScope())) {
                        return true;
                     }
                  }
               }
            }
         }
         return false;
      }

      boolean blockMayModify(LabelRef blockLabel, Set<VariableRef> operands) {
         ensureWriteInfoBuilt();
         if(globalClobberBlocks.contains(blockLabel)) {
            return true;
         }
         for(VariableRef vr : operands) {
            Set<LabelRef> vrBlocks = varWriteBlocks.get(vr);
            if(vrBlocks != null && vrBlocks.contains(blockLabel)) {
               return true;
            }
         }
         return false;
      }
   }

   @Override
   public boolean step() {
      boolean modified = false;

      // Dominators are used for determining if one statement is guaranteed to be executed before another statement
      DominatorsGraph dominators = getProgram().getDominators();

      for(ProcedureCompilation procComp : getProgram().getProcedureCompilations()) {
         ControlFlowGraph procGraph = procComp.getGraph();
         if(procGraph == null) continue;
         List<Graph.Block> procBlocks = procGraph.getAllBlocks();
         if(procBlocks.isEmpty()) continue;

         // All RValues in this procedure indexed by their signature (CSE is strictly intra-procedural)
         Map<RValueKey, List<AssignmentWithRValue>> rValues = new HashMap<>();
         ProcCfgCache cfgCache = new ProcCfgCache(procBlocks);

         for(Graph.Block block : procBlocks) {
            for(Statement statement : block.getStatements()) {
               if(statement instanceof StatementAssignment) {
                  StatementAssignment thisAssignment = (StatementAssignment) statement;
                  Operator op = thisAssignment.getOperator();
                  if(op == null || op instanceof OperatorCast) continue;
                  if(Operators.LE.equals(op) || Operators.LT.equals(op) || Operators.GE.equals(op) || Operators.GT.equals(op)
                        || Operators.EQ.equals(op) || Operators.NEQ.equals(op) || Operators.LOGIC_NOT.equals(op)
                        || Operators.LOGIC_AND.equals(op) || Operators.LOGIC_OR.equals(op)
                        || Operators.BYTE0.equals(op) || Operators.BYTE1.equals(op) || Operators.BYTE2.equals(op) || Operators.BYTE3.equals(op)
                        || Operators.WORD0.equals(op) || Operators.WORD1.equals(op)) {
                     continue;
                  }
                  RValue rVal1 = thisAssignment.getrValue1();
                  RValue rVal2 = thisAssignment.getrValue2();
                  if((rVal1 == null || rVal1 instanceof ConstantValue) && (rVal2 == null || rVal2 instanceof ConstantValue)) {
                     continue;
                  }
                  AssignmentWithRValue thisRValue = new AssignmentWithRValue(thisAssignment, block);
                  if(!thisRValue.isConstant() && !thisRValue.isTrivial() && !thisRValue.isVolatile()) {
                     boolean duplicate = false;
                     RValueKey key = new RValueKey(thisRValue.rValue1, thisRValue.operator, thisRValue.rValue2);
                     List<AssignmentWithRValue> candidates = rValues.get(key);
                     if(candidates != null) {
                        for(AssignmentWithRValue otherRValue : candidates) {
                           if(otherRValue.isSameRValue(thisRValue)) {
                              // Check if any of the statements is guaranteed to be executed before the other statement
                              DominatorsBlock thisDominators = dominators.getDominators(thisRValue.block.getLabel());
                              DominatorsBlock otherDominators = dominators.getDominators(otherRValue.block.getLabel());
                              if(otherRValue.block.getLabel().equals(thisRValue.block.getLabel())) {
                                 // The two assignments are in the same block
                                 // Since we are visiting statements sequentially the other assignment is guaranteed to be executed before this assignment
                                 if(isSafeSameBlock(otherRValue, thisRValue))
                                    duplicate |= deduplicateRValue(otherRValue, thisRValue);
                              } else {
                                 if(thisDominators.contains(otherRValue.block.getLabel())) {
                                    // The other assignment is guaranteed to be executed before this assignment
                                    if(scopeMatch(thisRValue, otherRValue) && isSafeCrossBlock(otherRValue, thisRValue, cfgCache))
                                        duplicate |= deduplicateRValue(otherRValue, thisRValue);
                                 } else if(otherDominators.contains(thisRValue.block.getLabel())) {
                                    // This assignment is guaranteed to be executed before the other assignment
                                    if(scopeMatch(thisRValue, otherRValue) && isSafeCrossBlock(thisRValue, otherRValue, cfgCache))
                                        duplicate |= deduplicateRValue(thisRValue, otherRValue);
                                 }
                              }
                           }
                        }
                     }
                     // If not a duplicate itself - add it to the candidate pool
                     if(!duplicate)
                        rValues.computeIfAbsent(key, k -> new ArrayList<>()).add(thisRValue);
                     // Determine if anything is modified
                     modified |= duplicate;
                  }
               }
            }
         }
      }
      return modified;
   }

   /**
    * Check that the scopes match for all involved variables.
    *
    * @param thisRValue This RValue
    * @param otherRValue This duplicate RValue
    * @return true if all scopes match
    */
   private boolean scopeMatch(AssignmentWithRValue thisRValue, AssignmentWithRValue otherRValue) {
      if(!thisRValue.block.getScope().equals(otherRValue.block.getScope()))
         return false;
      ScopeRef scope = thisRValue.block.getScope();
      return
            scopeMatch(thisRValue.assignment.getlValue(), scope) &&
                  scopeMatch(otherRValue.assignment.getlValue(), scope) &&
                  scopeMatch(thisRValue.rValue1, scope) &&
                  scopeMatch(thisRValue.rValue2, scope);
   }

   /**
    * Check that the scope of the values inside an RValue matches the scope of a block.
    * We only know that a specific variable version has the same value if it is from the same scope as the block.
    * Otherwise it might be modified inside some called function.
    *
    * @param value The RValue to examine (may be null)
    * @param block The block to examine
    * @return true if the scope match
    */
   private boolean scopeMatch(Value value, ScopeRef scopeRef) {
      if(value == null)
         return true;
      List<SymbolVariableRef> varRefs = new ArrayList<>();
      ProgramValueIterator.execute(new ProgramValue.GenericValue(value),
            (programValue, currentStmt, stmtIt, currentBlock) -> {
               if(programValue.get() instanceof SymbolVariableRef) varRefs.add((SymbolVariableRef) programValue.get());
            }
            , null, null, null);
      for(SymbolVariableRef varRef : varRefs) {
         Variable var = getProgramScope().getVariable(varRef);
         if(!var.getScope().getRef().equals(scopeRef))
            return false;
      }
      return true;
   }

   /**
    * De-duplicates the RValues of two assignments, where the first one is guaranteed to be called before the second one.
    * Modifies the second assignment to reference the result of the first.
    *
    * @param firstRValue The first assignment with the RValue - guaranteed to be called before the second assignment.
    * @param secondRValue A second assignment with a duplicated RValue.
    * @return true if the deduplication was successful.
    */
   private boolean deduplicateRValue(AssignmentWithRValue firstRValue, AssignmentWithRValue secondRValue) {
      LValue firstLVal = firstRValue.assignment.getlValue();
      if(firstLVal instanceof VariableRef firstLVarRef) {
         Variable firstLVar = getProgramScope().getVariable(firstLVarRef);
         if(firstLVar == null || firstLVar.isVolatile() || firstLVar.getMemoryAddress() != null) {
            return false; // SSA-03: Never read back from a volatile or hardware-mapped LValue
         }
         StatementAssignment secondAssignment = secondRValue.assignment;
         getLog().append("Identified duplicate assignment right side " + secondAssignment.toString(getProgram(), false));
         secondAssignment.setrValue1(null);
         secondAssignment.setOperator(null);
         secondAssignment.setrValue2(firstLVal);
         return true;
      }
      return false;
   }


   /**
    * Represents an RValue of an assignment.
    */
   private class AssignmentWithRValue {
      private Graph.Block block;
      private StatementAssignment assignment;
      private RValue rValue1;
      private Operator operator;
      private RValue rValue2;

      public AssignmentWithRValue(StatementAssignment assignment, Graph.Block block) {
         this.block = block;
         this.assignment = assignment;
         this.rValue1 = assignment.getrValue1();
         this.operator = assignment.getOperator();
         this.rValue2 = assignment.getrValue2();
      }

      private boolean isConstant() {
         if(rValue1 != null && !(rValue1 instanceof ConstantValue))
            return false;
         if(rValue2 != null && !(rValue2 instanceof ConstantValue))
            return false;
         return true;
      }

      private boolean isLoadStore() {
         return isKindLoadStore(rValue1) || isKindLoadStore(rValue2) || isKindLoadStore(assignment.getlValue());
      }

      private boolean isKindLoadStore(Value val) {
         if(val == null) return false;
         if(val instanceof VariableRef) {
            Variable var = getProgramScope().getVar((VariableRef) val);
            return var != null && var.isKindLoadStore();
         }
         if(val instanceof CastValue) {
            return isKindLoadStore(((CastValue) val).getValue());
         }
         return false;
      }

      private boolean isVolatile() {
         if(Operators.DEREF.equals(operator) || Operators.DEREF_IDX.equals(operator))
            return true;
         if(isVolatileVal(rValue1) || isVolatileVal(rValue2))
            return true;
         return false;
      }

      private boolean isSafeCseSource() {
         LValue lv = assignment.getlValue();
         if(!(lv instanceof VariableRef vr)) return false;
         Variable var = getProgramScope().getVariable(vr);
         return var != null && !var.isVolatile() && var.getMemoryAddress() == null;
      }

      private boolean isVolatileVal(Value val) {
         if(val == null) return false;
         if(val instanceof PointerDereference) return true;
         if(val instanceof VariableRef) {
            Variable variable = getProgramScope().getVariable((VariableRef) val);
            return variable != null && variable.isVolatile();
         }
         if(val instanceof CastValue) {
            return isVolatileVal(((CastValue) val).getValue());
         }
         return false;
      }

      private boolean isTrivial() {
         if(operator == null) return true;
         if(operator instanceof OperatorCast) return true;
         if(operator.equals(Operators.LE)) return true;
         if(operator.equals(Operators.LT)) return true;
         if(operator.equals(Operators.GE)) return true;
         if(operator.equals(Operators.GT)) return true;
         if(operator.equals(Operators.EQ)) return true;
         if(operator.equals(Operators.NEQ)) return true;
         if(operator.equals(Operators.LOGIC_NOT)) return true;
         if(operator.equals(Operators.LOGIC_AND)) return true;
         if(operator.equals(Operators.LOGIC_OR)) return true;
         if(operator.equals(Operators.BYTE0)) return true;
         if(operator.equals(Operators.BYTE1)) return true;
         if(operator.equals(Operators.BYTE2)) return true;
         if(operator.equals(Operators.BYTE3)) return true;
         if(operator.equals(Operators.WORD0)) return true;
         if(operator.equals(Operators.WORD1)) return true;
         if(operator.equals(Operators.PLUS) && rValue2 instanceof ConstantValue) {
            final SymbolType type1 = inferValueType(rValue1);
            return type1 != null && type1.getSizeBytes() == 1;
         }
         if(operator.equals(Operators.PLUS) && rValue1 instanceof ConstantValue) {
            final SymbolType type2 = inferValueType(rValue2);
            return type2 != null && type2.getSizeBytes() == 1;
         }
         return false;
      }

      private SymbolType inferValueType(RValue rValue) {
         if(rValue instanceof VariableRef) {
            Variable var = getProgramScope().getVariable((VariableRef) rValue);
            if(var != null) return var.getType();
         }
         return SymbolTypeInference.inferType(getProgramScope(), rValue);
      }


      /**
       * Determines if two assignment RValues are duplicates.
       *
       * @param that The other assignment
       * @return true if the RValues are duplicates
       */
      public boolean isSameRValue(AssignmentWithRValue that) {
         return Objects.equals(rValue1, that.rValue1) &&
               Objects.equals(operator, that.operator) &&
               Objects.equals(rValue2, that.rValue2);
      }

      @Override
      public boolean equals(Object o) {
         if(this == o) return true;
         if(o == null || getClass() != o.getClass()) return false;
         AssignmentWithRValue that = (AssignmentWithRValue) o;
         return block.equals(that.block) &&
               assignment.equals(that.assignment) &&
               Objects.equals(rValue1, that.rValue1) &&
               Objects.equals(operator, that.operator) &&
               Objects.equals(rValue2, that.rValue2);
      }

      @Override
      public int hashCode() {
         return Objects.hash(block, assignment, rValue1, operator, rValue2);
      }
   }

   private boolean statementMayModifyOperands(Statement s, Set<VariableRef> operands, boolean hasLoadStoreOperand) {
      if(s instanceof StatementAssignment sa) {
         LValue lVal = sa.getlValue();
         if(lVal instanceof VariableRef vr && operands.contains(vr)) {
            return true;
         }
         // SSA-03: Indirect pointer write (*p = val) can alias any LOAD_STORE variable
         if(lVal instanceof PointerDereference && hasLoadStoreOperand) {
            return true;
         }
      } else if(s instanceof StatementCall || s instanceof StatementCallExecute || s instanceof StatementCalling || s instanceof StatementCallPointer) {
         // SSA-03: Calls can modify global, static, __ma, or address-taken LOAD_STORE variables
         if(hasLoadStoreOperand) {
            return true;
         }
      } else if(s instanceof StatementAsm || s instanceof StatementKickAsm) {
         return true;
      }
      return false;
   }

   private boolean isSafeSameBlock(AssignmentWithRValue first, AssignmentWithRValue second) {
      if(!first.isSafeCseSource()) return false;
      boolean hasLoadStore = first.isLoadStore() || second.isLoadStore();
      if(!hasLoadStore) return true;

      List<Statement> stmts = first.block.getStatements();
      int idx1 = stmts.indexOf(first.assignment);
      int idx2 = stmts.indexOf(second.assignment);
      if(idx1 < 0 || idx2 < 0 || idx1 >= idx2) return false;

      // Collect all variables referenced by the RValue operands AND first's LValue (if LOAD_STORE)
      Set<VariableRef> operands = new HashSet<>();
      collectVarRefs(first.rValue1, operands);
      collectVarRefs(first.rValue2, operands);
      collectVarRefs(first.assignment.getlValue(), operands);

      // Check all statements strictly between idx1 and idx2
      for(int i = idx1 + 1; i < idx2; i++) {
         if(statementMayModifyOperands(stmts.get(i), operands, hasLoadStore)) {
            return false;
         }
      }
      return true;
   }

   private static void collectVarRefs(Value val, Set<VariableRef> out) {
      if(val == null) return;
      if(val instanceof VariableRef) {
         out.add((VariableRef) val);
      } else if(val instanceof CastValue) {
         collectVarRefs(((CastValue) val).getValue(), out);
      }
   }

   private boolean isSafeCrossBlock(AssignmentWithRValue first, AssignmentWithRValue second, ProcCfgCache cfgCache) {
      if(!first.isSafeCseSource()) return false;
      boolean hasLoadStore = first.isLoadStore() || second.isLoadStore();
      if(!hasLoadStore) return true;

      // Collect all variables referenced by the RValue operands AND first's LValue (if LOAD_STORE)
      Set<VariableRef> operands = new HashSet<>();
      collectVarRefs(first.rValue1, operands);
      collectVarRefs(first.rValue2, operands);
      collectVarRefs(first.assignment.getlValue(), operands);

      // Check statements in first.block after first.assignment
      List<Statement> firstStmts = first.block.getStatements();
      int idx1 = firstStmts.indexOf(first.assignment);
      if(idx1 >= 0) {
         for(int i = idx1 + 1; i < firstStmts.size(); i++) {
            if(statementMayModifyOperands(firstStmts.get(i), operands, hasLoadStore)) {
               return false;
            }
         }
      }

      // Check statements in second.block before second.assignment
      List<Statement> secondStmts = second.block.getStatements();
      int idx2 = secondStmts.indexOf(second.assignment);
      if(idx2 >= 0) {
         for(int i = 0; i < idx2; i++) {
            if(statementMayModifyOperands(secondStmts.get(i), operands, hasLoadStore)) {
               return false;
            }
         }
      }

      // Check precomputed per-procedure clobber/write blocks (and second.block after idx2 for loops)
      ScopeRef scope = first.block.getScope();
      LabelRef firstLabel = first.block.getLabel();
      LabelRef secondLabel = second.block.getLabel();
      boolean secondModifiesAfterIdx2 = false;
      if(idx2 >= 0) {
         for(int i = idx2 + 1; i < secondStmts.size(); i++) {
            if(statementMayModifyOperands(secondStmts.get(i), operands, hasLoadStore)) {
               secondModifiesAfterIdx2 = true;
               break;
            }
         }
      }

      if(!secondModifiesAfterIdx2 && !cfgCache.hasOtherWriteCandidate(scope, firstLabel, secondLabel, operands)) {
         return true;
      }

      // SSA-03: Check ALL paths from first.block to second.block INCLUDING loop back-edges returning to second.block
      // without re-executing first.block! Uses O(1) procedure-local blockMap, predMap & write info.
      Set<LabelRef> forwardFromFirst = new HashSet<>();
      Queue<LabelRef> fwdQueue = new ArrayDeque<>();
      Graph.Block firstBlock = cfgCache.getBlock(firstLabel);
      if(firstBlock != null) {
         for(LabelRef succ : firstBlock.getSuccessors()) {
            if(succ != null && !succ.equals(firstLabel) && forwardFromFirst.add(succ)) {
               fwdQueue.add(succ);
            }
         }
      }
      while(!fwdQueue.isEmpty()) {
         LabelRef cur = fwdQueue.poll();
         Graph.Block curBlock = cfgCache.getBlock(cur);
         if(curBlock != null && scope.equals(curBlock.getScope())) {
            for(LabelRef succ : curBlock.getSuccessors()) {
               if(succ != null && !succ.equals(firstLabel) && forwardFromFirst.add(succ)) {
                  fwdQueue.add(succ);
               }
            }
         }
      }

      Set<LabelRef> backwardToSecond = new HashSet<>();
      Queue<LabelRef> bwdQueue = new ArrayDeque<>();
      for(LabelRef predLbl : cfgCache.getPredecessors(secondLabel)) {
         Graph.Block predBlock = cfgCache.getBlock(predLbl);
         if(predBlock != null && !predLbl.equals(firstLabel) && scope.equals(predBlock.getScope()) && backwardToSecond.add(predLbl)) {
            if(predLbl.equals(secondLabel)) {
               if(secondModifiesAfterIdx2) return false;
            } else if(forwardFromFirst.contains(predLbl) && cfgCache.blockMayModify(predLbl, operands)) {
               return false;
            }
            bwdQueue.add(predLbl);
         }
      }
      while(!bwdQueue.isEmpty()) {
         LabelRef cur = bwdQueue.poll();
         for(LabelRef predLbl : cfgCache.getPredecessors(cur)) {
            Graph.Block predBlock = cfgCache.getBlock(predLbl);
            if(predBlock != null && !predLbl.equals(firstLabel) && scope.equals(predBlock.getScope()) && backwardToSecond.add(predLbl)) {
               if(predLbl.equals(secondLabel)) {
                  if(secondModifiesAfterIdx2) return false;
               } else if(forwardFromFirst.contains(predLbl) && cfgCache.blockMayModify(predLbl, operands)) {
                  return false;
               }
               bwdQueue.add(predLbl);
            }
         }
      }

      return true;
   }
}
