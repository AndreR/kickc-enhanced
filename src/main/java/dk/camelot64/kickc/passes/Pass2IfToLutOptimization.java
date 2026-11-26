package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.Comment;
import dk.camelot64.kickc.model.ConstantNotLiteral;
import dk.camelot64.kickc.model.ControlFlowBlock;
import dk.camelot64.kickc.model.ControlFlowGraph;
import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.ProcedureCompilation;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.VariableBuilder;
import dk.camelot64.kickc.model.VariableReferenceInfos;
import dk.camelot64.kickc.model.iterator.ProgramValueIterator;
import dk.camelot64.kickc.model.operators.Operator;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.statements.StatementConditionalJump;
import dk.camelot64.kickc.model.statements.StatementPhiBlock;
import dk.camelot64.kickc.model.symbols.ArraySpec;
import dk.camelot64.kickc.model.symbols.Label;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.ProgramScope;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Symbol;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.values.SymbolRef;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeInference;
import dk.camelot64.kickc.model.types.SymbolTypeIntegerFixed;
import dk.camelot64.kickc.model.types.SymbolTypePointer;
import dk.camelot64.kickc.model.values.CastValue;
import dk.camelot64.kickc.model.values.ConstantArrayList;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.model.values.ConstantLiteral;
import dk.camelot64.kickc.model.values.ConstantValue;
import dk.camelot64.kickc.model.values.LabelRef;
import dk.camelot64.kickc.model.values.PointerDereferenceIndexed;
import dk.camelot64.kickc.model.values.RValue;
import dk.camelot64.kickc.model.values.Value;
import dk.camelot64.kickc.model.values.VariableRef;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Converts dense contiguous 8-bit if-else-if equality chains that only assign constants
 * into a single bounds-checked (or direct when VRP-guaranteed) constant lookup table access.
 *
 * Pattern matched (4 <= N <= 64 contiguous keys in [base, base + N - 1]):
 *   if (idx == base) dst = c0;
 *   else if (idx == base + 1) dst = c1;
 *   ...
 *   else dst = defaultVal;
 *
 * Transformed to:
 *   const byte __lut_k[N] = { c0, c1, ... };
 *   if ((idx - base) < N) dst = __lut_k[idx - base]; else dst = defaultVal;
 *   (or direct `dst = __lut_k[idx - base]` without a branch when VRP proves idx in [base, base + N - 1]).
 */
public class Pass2IfToLutOptimization extends Pass2SsaOptimization {

   private static final int MIN_LUT_CASES = 4;
   private static final int MAX_LUT_CASES = 64;
   private static final long MAX_BASE_OFFSET = 128L;

   private static final class EqCondition {
      final VariableRef idxRef;
      final long key;
      final LabelRef trueTarget;
      final LabelRef falseNext;

      EqCondition(VariableRef idxRef, long key, LabelRef trueTarget, LabelRef falseNext) {
         this.idxRef = idxRef;
         this.key = key;
         this.trueTarget = trueTarget;
         this.falseNext = falseNext;
      }
   }

   private static final class ChainLink {
      final Graph.Block condBlock;
      final StatementConditionalJump jumpStmt;
      final EqCondition cond;
      Graph.Block branchBlock;
      StatementAssignment branchAssign;
      VariableRef branchAssignedVar;

      ChainLink(Graph.Block condBlock, StatementConditionalJump jumpStmt, EqCondition cond) {
         this.condBlock = condBlock;
         this.jumpStmt = jumpStmt;
         this.cond = cond;
      }
   }

   public Pass2IfToLutOptimization(Program program) {
      super(program);
   }

   @Override
   public boolean step() {
      Map<VariableRef, Pass2RangeNarrowing.Interval> intervals = Pass2RangeNarrowing.computeVariableIntervals(getProgram());
      VariableReferenceInfos refInfos = getProgram().getVariableReferenceInfos();

      for(ProcedureCompilation pc : getProgram().getProcedureCompilations()) {
         ControlFlowGraph procGraph = pc.getGraph();
         if(procGraph == null) continue;
         Map<LabelRef, List<Graph.Block>> predMap = buildPredecessorMap(procGraph);
         for(Graph.Block block : new ArrayList<>(procGraph.getAllBlocks())) {
            if(tryOptimizeChain(procGraph, block, predMap, intervals, refInfos)) {
               getProgram().clearVariableReferenceInfos();
               getProgram().clearStatementInfos();
               getProgram().clearControlFlowBlockSuccessorClosure();
               return true;
            }
         }
      }
      return false;
   }

   private boolean tryOptimizeChain(
         ControlFlowGraph procGraph,
         Graph.Block c0,
         Map<LabelRef, List<Graph.Block>> predMap,
         Map<VariableRef, Pass2RangeNarrowing.Interval> intervals,
         VariableReferenceInfos refInfos) {
      ProgramScope scope = getProgramScope();
      List<Statement> c0Stmts = c0.getStatements();
      if(c0Stmts.isEmpty()) return false;
      Statement lastC0 = c0Stmts.get(c0Stmts.size() - 1);
      if(!(lastC0 instanceof StatementConditionalJump j0)) return false;

      EqCondition cond0 = parseEqualityCondition(c0, j0, scope);
      if(cond0 == null) return false;

      VariableRef idxRef = cond0.idxRef;
      SymbolType idxType = SymbolTypeInference.inferType(scope, idxRef);
      if(!SymbolType.BYTE.equals(idxType) && !SymbolType.SBYTE.equals(idxType)) return false;
      Variable idxVar = scope.getVariable(idxRef);
      if(idxVar == null || idxVar.isVolatile() || idxVar.isKindLoadStore()) return false;

      // Collect the linear chain of equality conditions on idxRef
      List<ChainLink> links = new ArrayList<>();
      Set<LabelRef> condLabels = new HashSet<>();
      Set<Long> seenKeys = new HashSet<>();
      links.add(new ChainLink(c0, j0, cond0));
      condLabels.add(c0.getLabel());
      seenKeys.add(cond0.key);

      while(links.size() <= MAX_LUT_CASES) {
         ChainLink prev = links.get(links.size() - 1);
         Graph.Block nextBlock = getGraph().getBlock(prev.cond.falseNext);
         if(nextBlock == null || condLabels.contains(nextBlock.getLabel())) break;
         if(!Objects.equals(nextBlock.getScope(), c0.getScope())) break;
         if(nextBlock.getCallSuccessor() != null) break;
         List<Graph.Block> preds = predMap.get(nextBlock.getLabel());
         if(preds == null || preds.size() != 1 || preds.get(0) != prev.condBlock) break;

         StatementConditionalJump nextJmp = getSoleConditionalJump(nextBlock);
         if(nextJmp == null) break;

         EqCondition nextCond = parseEqualityCondition(nextBlock, nextJmp, scope);
         if(nextCond == null || !idxRef.equals(nextCond.idxRef)) break;
         if(!seenKeys.add(nextCond.key)) break;

         links.add(new ChainLink(nextBlock, nextJmp, nextCond));
         condLabels.add(nextBlock.getLabel());
      }

      int n = links.size();
      if(n < MIN_LUT_CASES || n > MAX_LUT_CASES) return false;

      // Verify keys form a contiguous range [base, base + n - 1] with small non-negative base
      long minKey = Long.MAX_VALUE;
      long maxKey = Long.MIN_VALUE;
      for(ChainLink link : links) {
         if(link.cond.key < minKey) minKey = link.cond.key;
         if(link.cond.key > maxKey) maxKey = link.cond.key;
      }
      if(maxKey - minKey + 1L != n) return false;
      long base = minKey;
      if(base < 0L || base > MAX_BASE_OFFSET) return false;
      if(SymbolType.BYTE.equals(idxType) && base + n > 256L) return false;
      if(SymbolType.SBYTE.equals(idxType) && base + n > 128L) return false;

      // Validate each branch target T_i
      Set<LabelRef> branchLabels = new HashSet<>();
      for(ChainLink link : links) {
         Graph.Block br = getGraph().getBlock(link.cond.trueTarget);
         if(br == null || condLabels.contains(br.getLabel()) || !branchLabels.add(br.getLabel())) return false;
         if(br.getConditionalSuccessor() != null || br.getCallSuccessor() != null) return false;
         LabelRef brSucc = br.getDefaultSuccessor();
         if(brSucc == null || SymbolRef.PROCEXIT_BLOCK_NAME.equals(brSucc.getFullName())) return false;
         List<Graph.Block> brPreds = predMap.get(br.getLabel());
         if(brPreds == null || brPreds.size() != 1 || brPreds.get(0) != link.condBlock) return false;

         StatementAssignment brAssign = getSingleConstantAssignment(br, scope, refInfos);
         if(brAssign == null && hasAnyNonEmptyPhiStatement(br)) return false;

         link.branchBlock = br;
         link.branchAssign = brAssign;
         link.branchAssignedVar = (brAssign != null && brAssign.getlValue() instanceof VariableRef vr) ? vr : null;
      }

      // Check merge topology: either Shape A (flat single merge M0) or Shape B (cascade M_{N-1} -> ... -> M_0)
      LabelRef tailFalseLabel = links.get(n - 1).cond.falseNext;
      Graph.Block m0 = getGraph().getBlock(links.get(0).branchBlock.getDefaultSuccessor());
      if(m0 == null || !m0.hasPhiBlock()) return false;

      boolean isFlatMerge = true;
      for(int i = 1; i < n; i++) {
         if(!m0.getLabel().equals(links.get(i).branchBlock.getDefaultSuccessor())) {
            isFlatMerge = false;
            break;
         }
      }

      List<Graph.Block> mergeChain = new ArrayList<>(n);
      Graph.Block elseBlock = null;
      if(isFlatMerge) {
         if(condLabels.contains(m0.getLabel()) || branchLabels.contains(m0.getLabel())) return false;
         mergeChain.add(m0);
         if(!tailFalseLabel.equals(m0.getLabel())) {
            elseBlock = validateElseBlock(tailFalseLabel, links.get(n - 1).condBlock, m0.getLabel(), condLabels, branchLabels, Set.of(m0.getLabel()), predMap);
            if(elseBlock == null) return false;
         }
      } else {
         // Shape B: M_{N-1} -> M_{N-2} -> ... -> M_0
         Set<LabelRef> mergeLabels = new HashSet<>();
         for(int i = 0; i < n; i++) {
            Graph.Block mi = getGraph().getBlock(links.get(i).branchBlock.getDefaultSuccessor());
            if(mi == null || condLabels.contains(mi.getLabel()) || branchLabels.contains(mi.getLabel()) || !mergeLabels.add(mi.getLabel())) {
               return false;
            }
            if(!mi.hasPhiBlock()) return false;
            mergeChain.add(mi);
         }
         Graph.Block mLast = mergeChain.get(n - 1);
         if(!tailFalseLabel.equals(mLast.getLabel())) {
            elseBlock = validateElseBlock(tailFalseLabel, links.get(n - 1).condBlock, mLast.getLabel(), condLabels, branchLabels, mergeLabels, predMap);
            if(elseBlock == null) return false;
         }
         LabelRef lastInnerPred = (elseBlock != null) ? elseBlock.getLabel() : links.get(n - 1).condBlock.getLabel();
         for(int i = n - 1; i >= 1; i--) {
            Graph.Block mi = mergeChain.get(i);
            Graph.Block prevMerge = mergeChain.get(i - 1);
            if(mi.getConditionalSuccessor() != null || mi.getCallSuccessor() != null) return false;
            if(!prevMerge.getLabel().equals(mi.getDefaultSuccessor())) return false;
            List<Statement> miStmts = mi.getStatements();
            if(miStmts.size() != 1 || !(miStmts.get(0) instanceof StatementPhiBlock)) return false;
            LabelRef expectedInnerPred = (i == n - 1) ? lastInnerPred : mergeChain.get(i + 1).getLabel();
            List<Graph.Block> miPreds = predMap.get(mi.getLabel());
            if(miPreds == null || miPreds.size() != 2) return false;
            LabelRef p0 = miPreds.get(0).getLabel();
            LabelRef p1 = miPreds.get(1).getLabel();
            LabelRef bLabel = links.get(i).branchBlock.getLabel();
            if(!((p0.equals(bLabel) && p1.equals(expectedInnerPred)) || (p1.equals(bLabel) && p0.equals(expectedInnerPred)))) {
               return false;
            }
         }
      }

      // Trace PHI variables in M_0 (and through M_1..M_{N-1} in Shape B)
      StatementPhiBlock phi0 = m0.getPhiBlock();
      if(phi0.getPhiVariables().isEmpty()) return false;

      StatementPhiBlock.PhiVariable dstPhi = null;
      long[] lutValues = new long[n];
      RValue dstElseVal = null;
      Map<StatementPhiBlock.PhiVariable, RValue> invariantPhiVals = new HashMap<>();
      Set<VariableRef> intermediateMergeVars = new LinkedHashSet<>();

      LabelRef finalElsePredInLastMerge = (elseBlock != null) ? elseBlock.getLabel() : links.get(n - 1).condBlock.getLabel();

      for(StatementPhiBlock.PhiVariable pv0 : phi0.getPhiVariables()) {
         RValue[] branchVals = new RValue[n];
         RValue elseVal;

         if(isFlatMerge) {
            for(int i = 0; i < n; i++) {
               RValue raw = pv0.getrValue(links.get(i).branchBlock.getLabel());
               if(raw == null) return false;
               branchVals[i] = resolveBranchValue(raw, links.get(i));
            }
            elseVal = pv0.getrValue(finalElsePredInLastMerge);
            if(elseVal == null) return false;
         } else {
            RValue raw0 = pv0.getrValue(links.get(0).branchBlock.getLabel());
            if(raw0 == null) return false;
            branchVals[0] = resolveBranchValue(raw0, links.get(0));

            RValue curDown = pv0.getrValue(mergeChain.get(1).getLabel());
            for(int i = 1; i < n; i++) {
               if(!(curDown instanceof VariableRef curVar)) return false;
               StatementPhiBlock phiI = mergeChain.get(i).getPhiBlock();
               StatementPhiBlock.PhiVariable pvI = phiI.getPhiVariable(curVar);
               if(pvI == null) return false;
               Collection<Integer> uses = refInfos.getVarUseStatements(curVar);
               if(uses != null && uses.size() > 1) return false;
               intermediateMergeVars.add(curVar);

               RValue rawI = pvI.getrValue(links.get(i).branchBlock.getLabel());
               if(rawI == null) return false;
               branchVals[i] = resolveBranchValue(rawI, links.get(i));

               LabelRef nextPred = (i == n - 1) ? finalElsePredInLastMerge : mergeChain.get(i + 1).getLabel();
               curDown = pvI.getrValue(nextPred);
               if(curDown == null) return false;
            }
            elseVal = curDown;
         }

         boolean allEqual = true;
         for(int i = 0; i < n; i++) {
            if(!Objects.equals(branchVals[i], elseVal)) {
               allEqual = false;
               break;
            }
         }
         if(allEqual) {
            invariantPhiVals.put(pv0, elseVal);
            continue;
         }

         if(dstPhi != null) return false; // More than one variable differs across branches

         SymbolType dstType = SymbolTypeInference.inferType(scope, pv0.getVariable());
         if(!SymbolType.BYTE.equals(dstType) && !SymbolType.SBYTE.equals(dstType)) return false;
         SymbolTypeIntegerFixed dstFixed = (SymbolTypeIntegerFixed) dstType;

         for(int i = 0; i < n; i++) {
            Long constVal = evaluateIntegerConstant(branchVals[i], scope);
            if(constVal == null || !dstFixed.contains(constVal)) return false;
            int lutIdx = (int) (links.get(i).cond.key - base);
            lutValues[lutIdx] = constVal;
         }
         dstPhi = pv0;
         dstElseVal = elseVal;
      }

      if(dstPhi == null) return false;

      // Ensure intermediate cascade merges M_1..M_{N-1} did not define any untracked PHI variables
      if(!isFlatMerge) {
         for(int i = 1; i < n; i++) {
            for(StatementPhiBlock.PhiVariable pvI : mergeChain.get(i).getPhiBlock().getPhiVariables()) {
               if(!intermediateMergeVars.contains(pvI.getVariable())) {
                  return false;
               }
            }
         }
      }

      // All checks passed! Perform the If-to-LUT transformation.
      SymbolTypeIntegerFixed elementType = (SymbolTypeIntegerFixed) SymbolTypeInference.inferType(scope, dstPhi.getVariable()).getQualified(false, false);
      Scope blockScope = scope.getScope(c0.getScope());
      Procedure enclosingProc = blockScope.getContainingProcedure();
      Scope constScope = (enclosingProc != null) ? enclosingProc : blockScope;

      String lutName = allocateLutName(constScope);
      List<ConstantValue> elements = new ArrayList<>(n);
      for(int i = 0; i < n; i++) {
         elements.add(new ConstantInteger(lutValues[i], elementType));
      }
      ConstantInteger arraySize = new ConstantInteger((long) n, SymbolType.BYTE);
      ConstantArrayList lutInit = new ConstantArrayList(elements, elementType, arraySize);
      SymbolTypePointer lutType = new SymbolTypePointer(elementType, new ArraySpec(arraySize), false, false);
      String constSegment = constScope.getSegmentConstData();
      Variable lutConst = Variable.createConstant(lutName, lutType, constScope, lutInit, constSegment);
      constScope.add(lutConst);

      // Determine whether VRP already proves base <= idx <= base + n - 1 and no explicit else block exists
      Pass2RangeNarrowing.Interval idxRange = intervals.get(idxRef);
      boolean skipBoundsCheck = (elseBlock == null) && idxRange != null && idxRange.fitsIn(base, base + n - 1L);

      // Normalize index to unsigned BYTE [0 .. n-1] in c0 before j0
      ListIterator<Statement> c0It = c0Stmts.listIterator(c0Stmts.size() - 1);
      RValue normIdx = idxRef;
      if(SymbolType.SBYTE.equals(idxType)) {
         Variable idxByteVar = VariableBuilder.createIntermediate(blockScope, SymbolType.BYTE, getProgram());
         StatementAssignment castStmt = new StatementAssignment(
               idxByteVar.getVariableRef(), null, null,
               new CastValue(SymbolType.BYTE, idxRef),
               true, j0.getSource(), Comment.NO_COMMENTS);
         c0It.add(castStmt);
         normIdx = idxByteVar.getVariableRef();
      }
      if(base != 0L) {
         Variable subVar = VariableBuilder.createIntermediate(blockScope, SymbolType.BYTE, getProgram());
         StatementAssignment subStmt = new StatementAssignment(
               subVar.getVariableRef(), normIdx, Operators.MINUS,
               new ConstantInteger(base, SymbolType.BYTE),
               true, j0.getSource(), Comment.NO_COMMENTS);
         c0It.add(subStmt);
         normIdx = subVar.getVariableRef();
      }

      Set<LabelRef> bypassedPreds = new HashSet<>();
      for(ChainLink link : links) {
         bypassedPreds.add(link.condBlock.getLabel());
         bypassedPreds.add(link.branchBlock.getLabel());
      }
      for(int i = 1; i < mergeChain.size(); i++) {
         bypassedPreds.add(mergeChain.get(i).getLabel());
      }

      Variable lutResVar = VariableBuilder.createIntermediate(blockScope, elementType, getProgram());
      StatementAssignment lutLoadAssign = new StatementAssignment(
            lutResVar.getVariableRef(), null, null,
            new PointerDereferenceIndexed(lutConst.getRef(), normIdx),
            true, j0.getSource(), j0.getComments());

      if(skipBoundsCheck) {
         c0It.add(lutLoadAssign);
         c0It.next(); // j0
         c0It.remove();
         c0.setConditionalSuccessor(null);
         c0.setCallSuccessor(null);
         c0.setDefaultSuccessor(m0.getLabel());

         updateMergePhi(phi0, bypassedPreds, dstPhi, lutResVar.getVariableRef(), c0.getLabel(), null, null, invariantPhiVals);
         getLog().append("Converted if-else-if chain (" + n + " cases, VRP-proven bounds) to direct LUT " + lutName);
      } else {
         Label lutLabel = blockScope.addLabelIntermediate();
         ControlFlowBlock lutBlock = new ControlFlowBlock(lutLabel.getRef(), c0.getScope());
         lutBlock.addStatement(lutLoadAssign);
         lutBlock.setDefaultSuccessor(m0.getLabel());
         procGraph.addBlock(lutBlock);

         j0.setrValue1(normIdx);
         j0.setOperator(Operators.LT);
         j0.setrValue2(new ConstantInteger((long) n, SymbolType.BYTE));
         j0.setDestination(lutLabel.getRef());
         c0.setConditionalSuccessor(lutLabel.getRef());

         LabelRef elsePredLabel;
         if(elseBlock != null) {
            c0.setDefaultSuccessor(elseBlock.getLabel());
            elseBlock.setDefaultSuccessor(m0.getLabel());
            elsePredLabel = elseBlock.getLabel();
         } else {
            c0.setDefaultSuccessor(m0.getLabel());
            elsePredLabel = c0.getLabel();
         }

         updateMergePhi(phi0, bypassedPreds, dstPhi, lutResVar.getVariableRef(), lutLabel.getRef(), dstElseVal, elsePredLabel, invariantPhiVals);
         getLog().append("Converted if-else-if chain (" + n + " cases) to bounds-checked LUT " + lutName);
      }

      // Remove bypassed blocks (C_1..C_{N-1}, T_0..T_{N-1}, M_1..M_{N-1}) and orphaned symbols
      Set<VariableRef> candidateDeadVars = new LinkedHashSet<>(intermediateMergeVars);
      for(ChainLink link : links) {
         if(link.branchAssignedVar != null) {
            candidateDeadVars.add(link.branchAssignedVar);
         }
         removeBlockAndLabel(procGraph, link.branchBlock);
      }
      for(int i = 1; i < links.size(); i++) {
         removeBlockAndLabel(procGraph, links.get(i).condBlock);
      }
      for(int i = 1; i < mergeChain.size(); i++) {
         removeBlockAndLabel(procGraph, mergeChain.get(i));
      }

      Set<VariableRef> stillReferencedVars = collectAllReferencedVars(getGraph());
      List<VariableRef> deadVars = new ArrayList<>();
      for(VariableRef v : candidateDeadVars) {
         if(!stillReferencedVars.contains(v)) {
            deadVars.add(v);
         }
      }
      if(!deadVars.isEmpty()) {
         deleteSymbols(scope, deadVars);
      }

      return true;
   }

   private void updateMergePhi(
         StatementPhiBlock phi0,
         Set<LabelRef> bypassedPreds,
         StatementPhiBlock.PhiVariable dstPhi,
         RValue lutResult,
         LabelRef lutPred,
         RValue elseVal,
         LabelRef elsePred,
         Map<StatementPhiBlock.PhiVariable, RValue> invariantPhiVals) {
      for(StatementPhiBlock.PhiVariable pv : phi0.getPhiVariables()) {
         Iterator<StatementPhiBlock.PhiRValue> it = pv.getValues().iterator();
         while(it.hasNext()) {
            StatementPhiBlock.PhiRValue prv = it.next();
            if(bypassedPreds.contains(prv.getPredecessor())) {
               it.remove();
            }
         }
         if(pv == dstPhi) {
            pv.setrValue(lutPred, lutResult);
            if(elsePred != null) {
               pv.setrValue(elsePred, elseVal);
            }
         } else {
            RValue common = invariantPhiVals.get(pv);
            pv.setrValue(lutPred, common);
            if(elsePred != null) {
               pv.setrValue(elsePred, common);
            }
         }
      }
   }

   private void removeBlockAndLabel(ControlFlowGraph procGraph, Graph.Block block) {
      LabelRef labelRef = block.getLabel();
      procGraph.remove(labelRef);
      Symbol sym = getProgramScope().getSymbol(labelRef);
      if(sym instanceof Label && sym.getScope() != null) {
         sym.getScope().remove(sym);
      }
   }

   private static Set<VariableRef> collectAllReferencedVars(Graph graph) {
      Set<VariableRef> used = new HashSet<>();
      ProgramValueIterator.execute(graph, (programValue, currentStmt, stmtIt, currentBlock) -> {
         Value val = programValue.get();
         if(val instanceof VariableRef vr) {
            used.add(vr);
         }
      });
      return used;
   }

   private static String allocateLutName(Scope constScope) {
      int id = 0;
      String name = "__lut_0";
      while(constScope.getLocalSymbol(name) != null) {
         name = "__lut_" + (++id);
      }
      return name;
   }

   private Graph.Block validateElseBlock(
         LabelRef elseLabel,
         Graph.Block lastCondBlock,
         LabelRef expectedMergeLabel,
         Set<LabelRef> condLabels,
         Set<LabelRef> branchLabels,
         Set<LabelRef> mergeLabels,
         Map<LabelRef, List<Graph.Block>> predMap) {
      if(condLabels.contains(elseLabel) || branchLabels.contains(elseLabel) || mergeLabels.contains(elseLabel)) {
         return null;
      }
      Graph.Block elseBlock = getGraph().getBlock(elseLabel);
      if(elseBlock == null) return null;
      if(elseBlock.getConditionalSuccessor() != null || elseBlock.getCallSuccessor() != null) return null;
      if(!expectedMergeLabel.equals(elseBlock.getDefaultSuccessor())) return null;
      List<Graph.Block> preds = predMap.get(elseLabel);
      if(preds == null || preds.size() != 1 || preds.get(0) != lastCondBlock) return null;
      return elseBlock;
   }

   private static StatementConditionalJump getSoleConditionalJump(Graph.Block block) {
      List<Statement> stmts = block.getStatements();
      StatementConditionalJump found = null;
      for(Statement stmt : stmts) {
         if(stmt instanceof StatementPhiBlock phi) {
            if(!phi.getPhiVariables().isEmpty()) return null;
         } else if(stmt instanceof StatementConditionalJump jmp && found == null) {
            found = jmp;
         } else {
            return null;
         }
      }
      return found;
   }

   private static boolean hasAnyNonEmptyPhiStatement(Graph.Block block) {
      for(Statement stmt : block.getStatements()) {
         if(stmt instanceof StatementPhiBlock phi) {
            if(!phi.getPhiVariables().isEmpty()) return true;
         } else {
            return true;
         }
      }
      return false;
   }

   private static StatementAssignment getSingleConstantAssignment(Graph.Block block, ProgramScope scope, VariableReferenceInfos refInfos) {
      StatementAssignment found = null;
      for(Statement stmt : block.getStatements()) {
         if(stmt instanceof StatementPhiBlock phi) {
            if(!phi.getPhiVariables().isEmpty()) return null;
         } else if(stmt instanceof StatementAssignment assign && found == null) {
            if(assign.getOperator() != null || assign.getrValue1() != null) return null;
            if(!(assign.getlValue() instanceof VariableRef dstRef)) return null;
            Variable dstVar = scope.getVariable(dstRef);
            if(dstVar == null || dstVar.isVolatile() || dstVar.isKindLoadStore()) return null;
            if(evaluateIntegerConstant(assign.getrValue2(), scope) == null) return null;
            Collection<Integer> uses = refInfos.getVarUseStatements(dstRef);
            if(uses != null && uses.size() > 1) return null;
            found = assign;
         } else {
            return null;
         }
      }
      return found;
   }

   private static RValue resolveBranchValue(RValue raw, ChainLink link) {
      if(link.branchAssign != null && Objects.equals(link.branchAssignedVar, raw)) {
         return link.branchAssign.getrValue2();
      }
      return raw;
   }

   private EqCondition parseEqualityCondition(Graph.Block block, StatementConditionalJump jmp, ProgramScope scope) {
      Operator op = jmp.getOperator();
      if(!Operators.EQ.equals(op) && !Operators.NEQ.equals(op)) return null;
      RValue r1 = jmp.getrValue1();
      RValue r2 = jmp.getrValue2();
      if(r1 == null || r2 == null) return null;

      VariableRef varRef = null;
      Long key = null;
      if(r1 instanceof VariableRef vr && !(r1 instanceof ConstantValue)) {
         Long c = evaluateIntegerConstant(r2, scope);
         if(c != null) {
            varRef = vr;
            key = c;
         }
      } else if(r2 instanceof VariableRef vr && !(r2 instanceof ConstantValue)) {
         Long c = evaluateIntegerConstant(r1, scope);
         if(c != null) {
            varRef = vr;
            key = c;
         }
      }
      if(varRef == null || key == null) return null;

      LabelRef condSucc = block.getConditionalSuccessor();
      LabelRef defSucc = block.getDefaultSuccessor();
      if(condSucc == null || defSucc == null || condSucc.equals(defSucc)) return null;

      if(Operators.EQ.equals(op)) {
         return new EqCondition(varRef, key, condSucc, defSucc);
      } else {
         return new EqCondition(varRef, key, defSucc, condSucc);
      }
   }

   private static Long evaluateIntegerConstant(RValue val, ProgramScope scope) {
      if(val instanceof CastValue cast) {
         return evaluateIntegerConstant(cast.getValue(), scope);
      }
      if(val instanceof ConstantInteger ci) {
         return ci.getInteger();
      }
      if(val instanceof ConstantValue cv) {
         try {
            ConstantLiteral lit = cv.calculateLiteral(scope);
            if(lit instanceof ConstantInteger ci) {
               return ci.getInteger();
            }
         } catch(ConstantNotLiteral ignored) {
         }
      }
      return null;
   }

   private static Map<LabelRef, List<Graph.Block>> buildPredecessorMap(Graph graph) {
      Map<LabelRef, List<Graph.Block>> preds = new HashMap<>();
      for(Graph.Block block : graph.getAllBlocks()) {
         preds.put(block.getLabel(), new ArrayList<>());
      }
      for(Graph.Block block : graph.getAllBlocks()) {
         if(block.getDefaultSuccessor() != null) {
            List<Graph.Block> list = preds.get(block.getDefaultSuccessor());
            if(list != null) list.add(block);
         }
         if(block.getConditionalSuccessor() != null && !block.getConditionalSuccessor().equals(block.getDefaultSuccessor())) {
            List<Graph.Block> list = preds.get(block.getConditionalSuccessor());
            if(list != null) list.add(block);
         }
         if(block.getCallSuccessor() != null
               && !block.getCallSuccessor().equals(block.getDefaultSuccessor())
               && !block.getCallSuccessor().equals(block.getConditionalSuccessor())) {
            List<Graph.Block> list = preds.get(block.getCallSuccessor());
            if(list != null) list.add(block);
         }
      }
      return preds;
   }
}
