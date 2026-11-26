package dk.camelot64.kickc.passes.calcs;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementCalling;
import dk.camelot64.kickc.model.statements.StatementPhiBlock;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Symbol;
import dk.camelot64.kickc.model.values.LabelRef;
import dk.camelot64.kickc.model.values.ProcedureRef;
import dk.camelot64.kickc.model.values.VariableRef;

import java.util.*;

/**
 * Identify the alive intervals for all variables. Add the intervals to the ProgramScope.
 */
public class PassNCalcLiveRangeVariables extends PassNCalcBase<LiveRangeVariables> {

   public PassNCalcLiveRangeVariables(Program program) {
      super(program);
   }

   @Override
   public LiveRangeVariables calculate() {
      final Map<ProcedureRef, Collection<VariableRef>> procedureReferencedVars = calculateProcedureReferencedVars(getProgram());
      LiveRangeVariables liveRanges = new LiveRangeVariables(getProgram());

      Graph graph = getProgram().getGraph();
      List<Statement> allStatements = graph.getAllStatements();

      int maxStmtIdx = 0;
      for(Statement nextStmt : allStatements) {
         if(nextStmt.getIndex() > maxStmtIdx) {
            maxStmtIdx = nextStmt.getIndex();
         }
      }

      PreviousStatement[][] previousStatementsByStmtIdx = new PreviousStatement[maxStmtIdx + 1][];
      @SuppressWarnings("unchecked")
      Collection<VariableRef>[] definedVarsByStmtIdx = new Collection[maxStmtIdx + 1];
      ProcedureRef[] callProcByStmtIdx = new ProcedureRef[maxStmtIdx + 1];
      ProcedureRef[] beforeMethodProcByStmtIdx = new ProcedureRef[maxStmtIdx + 1];

      VariableReferenceInfos referenceInfo = getProgram().getVariableReferenceInfos();
      StatementInfos statementInfos = getProgram().getStatementInfos();
      dk.camelot64.kickc.model.symbols.ProgramScope scope = getProgram().getScope();

      List<Graph.Block> allBlocks = graph.getAllBlocks();
      Map<LabelRef, List<Graph.Block>> predecessorMap = new HashMap<>(allBlocks.size() * 2);
      Statement[] prevInBlockByStmtIdx = new Statement[maxStmtIdx + 1];
      for(Graph.Block b : allBlocks) {
         if(b.getDefaultSuccessor() != null)
            predecessorMap.computeIfAbsent(b.getDefaultSuccessor(), k -> new ArrayList<>()).add(b);
         if(b.getConditionalSuccessor() != null)
            predecessorMap.computeIfAbsent(b.getConditionalSuccessor(), k -> new ArrayList<>()).add(b);
         if(b.getCallSuccessor() != null)
            predecessorMap.computeIfAbsent(b.getCallSuccessor(), k -> new ArrayList<>()).add(b);
         Statement prev = null;
         for(Statement s : b.getStatements()) {
            prevInBlockByStmtIdx[s.getIndex()] = prev;
            prev = s;
         }
      }

      for(Statement nextStmt : allStatements) {
         int idx = nextStmt.getIndex();
         Collection<PreviousStatement> prevs = getPreviousStatements(nextStmt, graph, statementInfos, predecessorMap, prevInBlockByStmtIdx);
         previousStatementsByStmtIdx[idx] = prevs.toArray(new PreviousStatement[prevs.size()]);
         definedVarsByStmtIdx[idx] = referenceInfo.getDefinedVars(nextStmt);

         if(nextStmt instanceof StatementCalling) {
            ProcedureRef procedure = ((StatementCalling) nextStmt).getProcedure();
            if(procedure != null) {
               callProcByStmtIdx[idx] = procedure;
            }
         }

         Graph.Block procBlock = statementInfos.getBlock(nextStmt);
         if(procBlock != null && procBlock.getLabel() != null) {
            Symbol sym = scope.getSymbol(procBlock.getLabel());
            if(sym instanceof Procedure) {
               beforeMethodProcByStmtIdx[idx] = ((Procedure) sym).getRef();
            }
         }
      }

      Map<VariableRef, Integer> varIdMap = new HashMap<>();
      List<VariableRef> varsByIdList = new ArrayList<>();
      BitSet[] aliveBitsByStmtIdx = new BitSet[maxStmtIdx + 1];
      for(int i = 0; i <= maxStmtIdx; i++) {
         aliveBitsByStmtIdx[i] = new BitSet();
      }

      initializeLiveRanges(liveRanges, allStatements, previousStatementsByStmtIdx, definedVarsByStmtIdx, aliveBitsByStmtIdx, referenceInfo, statementInfos, varIdMap, varsByIdList);

      int numVars = varsByIdList.size();
      VariableRef[] varsById = varsByIdList.toArray(new VariableRef[0]);

      BitSet[] definedBitsByStmtIdx = new BitSet[maxStmtIdx + 1];
      for(Statement nextStmt : allStatements) {
         int idx = nextStmt.getIndex();
         Collection<VariableRef> defined = definedVarsByStmtIdx[idx];
         if(defined != null && !defined.isEmpty()) {
            BitSet bs = new BitSet(numVars);
            for(VariableRef v : defined) {
               Integer id = varIdMap.get(v);
               if(id != null) {
                  bs.set(id);
               }
            }
            if(!bs.isEmpty()) {
               definedBitsByStmtIdx[idx] = bs;
            }
         }
      }

      Map<ProcedureRef, BitSet> procReferencedBits = new HashMap<>(procedureReferencedVars.size() * 2);
      for(Map.Entry<ProcedureRef, Collection<VariableRef>> entry : procedureReferencedVars.entrySet()) {
         Collection<VariableRef> refs = entry.getValue();
         if(refs != null && !refs.isEmpty()) {
            BitSet bs = new BitSet(numVars);
            for(VariableRef v : refs) {
               Integer id = varIdMap.get(v);
               if(id != null) {
                  bs.set(id);
               }
            }
            procReferencedBits.put(entry.getKey(), bs);
         } else {
            procReferencedBits.put(entry.getKey(), new BitSet(0));
         }
      }

      BitSet[] callProcReferencedBitsByStmtIdx = new BitSet[maxStmtIdx + 1];
      BitSet[] beforeMethodProcReferencedBitsByStmtIdx = new BitSet[maxStmtIdx + 1];
      for(Statement nextStmt : allStatements) {
         int idx = nextStmt.getIndex();
         if(callProcByStmtIdx[idx] != null) {
            callProcReferencedBitsByStmtIdx[idx] = procReferencedBits.get(callProcByStmtIdx[idx]);
         }
         if(beforeMethodProcByStmtIdx[idx] != null) {
            beforeMethodProcReferencedBitsByStmtIdx[idx] = procReferencedBits.get(beforeMethodProcByStmtIdx[idx]);
         }
      }

      BitSet scratch = new BitSet(numVars);
      boolean verbose = getLog().isVerboseLiveRanges();
      boolean propagating;
      do {
         propagating = calculateLiveRanges(
               allStatements,
               previousStatementsByStmtIdx,
               definedBitsByStmtIdx,
               callProcReferencedBitsByStmtIdx,
               beforeMethodProcReferencedBitsByStmtIdx,
               aliveBitsByStmtIdx,
               varsById,
               scratch,
               verbose);
         if(verbose) {
            materializeLiveRanges(liveRanges, aliveBitsByStmtIdx, varsById);
            getProgram().setLiveRangeVariables(liveRanges);
            getLog().append("Propagating live ranges...");
            getLog().append("CONTROL FLOW GRAPH - LIVE RANGES IN PROGRESS");
            getLog().append(getProgram().getGraph().toString(getProgram()));
         }
      } while(propagating);

      materializeLiveRanges(liveRanges, aliveBitsByStmtIdx, varsById);
      getProgram().setLiveRangeVariables(liveRanges);
      return liveRanges;
   }

   private static int getOrAssignVarId(VariableRef var, Map<VariableRef, Integer> varIdMap, List<VariableRef> varsById) {
      Integer id = varIdMap.get(var);
      if(id == null) {
         id = varsById.size();
         varIdMap.put(var, id);
         varsById.add(var);
      }
      return id;
   }

   private static void materializeLiveRanges(LiveRangeVariables liveRanges, BitSet[] aliveBitsByStmtIdx, VariableRef[] varsById) {
      for(int stmtIdx = 0; stmtIdx < aliveBitsByStmtIdx.length; stmtIdx++) {
         BitSet alive = aliveBitsByStmtIdx[stmtIdx];
         if(alive != null && !alive.isEmpty()) {
            for(int varId = alive.nextSetBit(0); varId >= 0; varId = alive.nextSetBit(varId + 1)) {
               liveRanges.addAlive(varsById[varId], stmtIdx);
            }
         }
      }
   }

   /**
    * Calculate the variables referenced inside each procedure and all it's sub-calls.
    */
   public static Map<ProcedureRef, Collection<VariableRef>> calculateProcedureReferencedVars(Program program) {
      VariableReferenceInfos referenceInfo = program.getVariableReferenceInfos();
      final ControlFlowBlockSuccessorClosure blockSuccessorClosure = program.getControlFlowBlockSuccessorClosure();
      Collection<Procedure> allProcedures = program.getScope().getAllProcedures(true);
      Map<ProcedureRef, Collection<VariableRef>> procReferencedVars = new LinkedHashMap<>();
      for(Procedure procedure : allProcedures) {
         Collection<VariableRef> referencedVars;
         if(procedure.isDeclaredIntrinsic())
            referencedVars = Collections.emptySet();
         else 
            referencedVars = blockSuccessorClosure.getSuccessorClosureReferencedVars(procedure.getRef().getLabelRef(), referenceInfo);
         procReferencedVars.put(procedure.getRef(), referencedVars);
      }
      return procReferencedVars;
   }

   /**
    * Runs through all statements propagating variable live ranges.
    * Variable live ranges of a statement is defined as all variables that are defined before or in the statement and used after the statement.
    * Variable live ranges are propagated through repeatedly executing the following for each statement:
    * <p>
    * <code>alive(stmt) = used(nextstmt) &cup; alive(nextstmt) &#x2216; defined(nextstmt)</code>
    * <p>
    * where
    * <ul>
    * <li>alive(stmt) is the alive variables of the statement</li>
    * <li>used(stmt) is the variables used in the statement</li>
    * <li>defined(stmt) is the variables defined by the statement</li>
    * <li>nextstmt is the statement following the statement stmt</li>
    * </ul>
    * <p>
    * Special consideration is giving when handling statements at the end/start of blocks, as multiple blocks may jump to the same block resulting in multiple stmt/nextstmt pairs.
    * Calls to methods are also given special consideration.
    * Variables used inside the method must be propagated back to all callers while variables not used inside the method must skip the method entirely back to the statement before the call.
    *
    * @param liveRanges The live ranges to propagate.
    * @return true if any live ranges was modified. false if no modification was performed (and the propagation is complete)
    */
   private void initializeLiveRanges(
         LiveRangeVariables liveRanges,
         List<Statement> allStatements,
         PreviousStatement[][] previousStatementsByStmtIdx,
         Collection<VariableRef>[] definedVarsByStmtIdx,
         BitSet[] aliveBitsByStmtIdx,
         VariableReferenceInfos referenceInfo,
         StatementInfos statementInfos,
         Map<VariableRef, Integer> varIdMap,
         List<VariableRef> varsByIdList) {
      for(Statement nextStmt : allStatements) {
         int nextIdx = nextStmt.getIndex();
         Collection<VariableRef> definedNextStmt = definedVarsByStmtIdx[nextIdx];
         initLiveRange(liveRanges, definedNextStmt, varIdMap, varsByIdList);
         PreviousStatement[] previousStmts = previousStatementsByStmtIdx[nextIdx];
         if(previousStmts != null) {
            for(PreviousStatement previousStmt : previousStmts) {
               if(PreviousStatement.Type.NORMAL.equals(previousStmt.getType()) || PreviousStatement.Type.BEFORE_METHOD.equals(previousStmt.getType())) {
                  initUsedVars(liveRanges, nextStmt, previousStmt, aliveBitsByStmtIdx, referenceInfo, statementInfos, varIdMap, varsByIdList);
               }
            }
         }
      }
   }

   private boolean calculateLiveRanges(
         List<Statement> allStatements,
         PreviousStatement[][] previousStatementsByStmtIdx,
         BitSet[] definedBitsByStmtIdx,
         BitSet[] callProcReferencedBitsByStmtIdx,
         BitSet[] beforeMethodProcReferencedBitsByStmtIdx,
         BitSet[] aliveBitsByStmtIdx,
         VariableRef[] varsById,
         BitSet scratch,
         boolean verbose) {
      boolean modified = false;
      for(int i = allStatements.size() - 1; i >= 0; i--) {
         Statement nextStmt = allStatements.get(i);
         int nextIdx = nextStmt.getIndex();
         BitSet aliveNextStmt = aliveBitsByStmtIdx[nextIdx];
         if(aliveNextStmt == null || aliveNextStmt.isEmpty()) {
            continue;
         }
         PreviousStatement[] previousStmts = previousStatementsByStmtIdx[nextIdx];
         if(previousStmts == null || previousStmts.length == 0) {
            continue;
         }
         BitSet definedNextStmt = definedBitsByStmtIdx[nextIdx];
         BitSet callProcReferenced = callProcReferencedBitsByStmtIdx[nextIdx];
         BitSet beforeMethodProcReferenced = beforeMethodProcReferencedBitsByStmtIdx[nextIdx];

         for(PreviousStatement previousStmt : previousStmts) {
            int prevIdx = previousStmt.getStatementIdx();
            if(prevIdx == nextIdx) continue;
            BitSet prevAlive = aliveBitsByStmtIdx[prevIdx];
            if(prevAlive == null) continue;

            PreviousStatement.Type prevType = previousStmt.getType();
            if(PreviousStatement.Type.NORMAL.equals(prevType)) {
               // Add all vars alive in the next statement
               BitSet candidates;
               if(definedNextStmt == null) {
                  candidates = aliveNextStmt;
               } else {
                  scratch.clear();
                  scratch.or(aliveNextStmt);
                  scratch.andNot(definedNextStmt);
                  candidates = scratch;
               }
               if(propagateBits(prevAlive, candidates, verbose, varsById, previousStmt, "Propagated alive var ")) {
                  modified = true;
               }
            } else if(PreviousStatement.Type.LAST_IN_METHOD.equals(prevType)) {
               // Add all vars that are referenced in the method
               if(callProcReferenced != null) {
                  scratch.clear();
                  scratch.or(aliveNextStmt);
                  scratch.and(callProcReferenced);
                  if(propagateBits(prevAlive, scratch, verbose, varsById, previousStmt, "Propagated alive var used in method into method ")) {
                     modified = true;
                  }
               }
            } else if(PreviousStatement.Type.SKIP_METHOD.equals(prevType)) {
               // Add all vars from next statement that the method does not use
               scratch.clear();
               scratch.or(aliveNextStmt);
               if(callProcReferenced != null) {
                  scratch.andNot(callProcReferenced);
               }
               if(definedNextStmt != null) {
                  scratch.andNot(definedNextStmt);
               }
               if(propagateBits(prevAlive, scratch, verbose, varsById, previousStmt, "Propagated alive var unused in method by skipping call ")) {
                  modified = true;
               }
            } else if(PreviousStatement.Type.BEFORE_METHOD.equals(prevType)) {
               // Add all alive variables to previous that are used inside the method
               if(beforeMethodProcReferenced != null) {
                  scratch.clear();
                  scratch.or(aliveNextStmt);
                  scratch.and(beforeMethodProcReferenced);
                  if(definedNextStmt != null) {
                     scratch.andNot(definedNextStmt);
                  }
                  if(propagateBits(prevAlive, scratch, verbose, varsById, previousStmt, "Propagated alive used in method out of method ")) {
                     modified = true;
                  }
               }
            }
         }
      }
      return modified;
   }

   private boolean propagateBits(
         BitSet prevAlive,
         BitSet candidates,
         boolean verbose,
         VariableRef[] varsById,
         PreviousStatement previousStmt,
         String verbosePrefix) {
      if(verbose) {
         BitSet newlyAdded = (BitSet) candidates.clone();
         newlyAdded.andNot(prevAlive);
         if(!newlyAdded.isEmpty()) {
            for(int id = newlyAdded.nextSetBit(0); id >= 0; id = newlyAdded.nextSetBit(id + 1)) {
               getLog().append(verbosePrefix + varsById[id] + " to " + previousStmt.getStatement());
            }
            prevAlive.or(newlyAdded);
            return true;
         }
         return false;
      }
      int before = prevAlive.cardinality();
      prevAlive.or(candidates);
      return prevAlive.cardinality() != before;
   }

   /**
    * Adds variables used in the next statement to the alive vars of the previous statement
    *
    * @param liveRanges The live ranges to be updated
    * @param nextStmt The next statement
    * @param previousStmt The previous statement
    * @return true if any live range modification was made
    */
   private boolean initUsedVars(
         LiveRangeVariables liveRanges,
         Statement nextStmt,
         PreviousStatement previousStmt,
         BitSet[] aliveBitsByStmtIdx,
         VariableReferenceInfos referenceInfo,
         StatementInfos statementInfos,
         Map<VariableRef, Integer> varIdMap,
         List<VariableRef> varsByIdList) {
      boolean modified = false;
      Collection<VariableRef> usedNextStmt = referenceInfo.getUsedVars(nextStmt);
      int prevIdx = previousStmt.getStatementIdx();
      BitSet prevAlive = aliveBitsByStmtIdx[prevIdx];
      if(nextStmt instanceof StatementPhiBlock) {
         // If next statement is a phi add the used variables to previous based on the phi entries
         StatementPhiBlock phi = (StatementPhiBlock) nextStmt;
         Graph.Block previousBlock = statementInfos.getBlock(previousStmt.getStatementIdx());
         for(StatementPhiBlock.PhiVariable phiVariable : phi.getPhiVariables()) {
            for(StatementPhiBlock.PhiRValue phiRValue : phiVariable.getValues()) {
               if(phiRValue.getPredecessor().equals(previousBlock.getLabel())) {
                  if(phiRValue.getrValue() instanceof VariableRef) {
                     VariableRef usedVar = (VariableRef) phiRValue.getrValue();
                     int varId = getOrAssignVarId(usedVar, varIdMap, varsByIdList);
                     if(!prevAlive.get(varId)) {
                        prevAlive.set(varId);
                        liveRanges.addAlive(usedVar, prevIdx);
                        modified = true;
                        if(getLog().isVerboseLiveRanges()) {
                           getLog().append("Adding used phi var " + usedVar + " to " + previousStmt.getStatement());
                        }
                     }
                  }
               }
            }
         }
      } else {
         // Not a phi block - add used vars to all previous blocks
         for(VariableRef usedVar : usedNextStmt) {
            int varId = getOrAssignVarId(usedVar, varIdMap, varsByIdList);
            if(!prevAlive.get(varId)) {
               prevAlive.set(varId);
               liveRanges.addAlive(usedVar, prevIdx);
               modified = true;
               if(getLog().isVerboseLiveRanges()) {
                  getLog().append("Adding used var " + usedVar + " to " + previousStmt.getStatement());
               }
            }
         }
      }
      return modified;
   }

   /**
    * Find the statement(s) executed just before the passed statement.
    * There may be multiple previous statements if the current statement is the first in a block or a call.
    * Special consideration is giving when handling statements at the end/start of blocks,
    * as multiple blocks may jump to the same block resulting in multiple stmt/nextstmt pairs.
    * Calls to methods are also given special consideration.
    * The following illustrates how the different types of previous statements work.
    * <pre>
    * b1: {
    * [1] stmt;         prev: b3[1]/NORMAL, b2[2]/NORMAL
    * [2] call b4;      prev: b1[1]/SKIP_METHOD, b4[3]/LAST_IN_METHOD
    * [3] stmt;         prev: b1[2]/NORMAL
    * goto b2;
    * }
    * b2: {
    * [1] stmt;         prev: b1[3]/NORMAL
    * [2] if(x) b1;     prev: b2[1]/NORMAL
    * goto b3;
    * }
    * b3: {
    * [1] stmt;         prev: b2[2]/NORMAL
    * goto b1
    * }
    * b4: {
    * [1] stmt;         prev: b1[1]/BEFORE_METHOD
    * [2] stmt;         prev: b4[1]/NORMAL
    * [3] return;       prev: b4[3]/NORMAL
    * }
    * </pre>    *
    *
    * @param statement The statement to find previous for
    * @return statement(s) executed just before the passed statement
    */
   private Collection<PreviousStatement> getPreviousStatements(
         Statement statement,
         Graph graph,
         StatementInfos statementInfos,
         Map<LabelRef, List<Graph.Block>> predecessorMap,
         Statement[] prevInBlockByStmtIdx) {
      ArrayList<PreviousStatement> previousStatements = new ArrayList<>();
      // Find the statement(s) just before the current statement (disregarding if the current statement is a call - this will be handled later)
      Collection<Statement> precedingStatements = getPrecedingStatement(statement, statementInfos, predecessorMap, prevInBlockByStmtIdx);
      if(statement instanceof StatementCalling) {
         // Add the statement(s) just before the call
         for(Statement precedingStatement : precedingStatements) {
            previousStatements.add(new PreviousStatement(precedingStatement, PreviousStatement.Type.SKIP_METHOD));
         }
         // Add the last statement of the called method
         StatementCalling call = (StatementCalling) statement;
         ProcedureRef procedure = call.getProcedure();
         if(procedure!=null) {
            LabelRef procedureReturnBlock = procedure.getReturnBlock();
            Graph.Block returnBlock = graph.getBlock(procedureReturnBlock);
            if(returnBlock != null) {
               Collection<Statement> lastStatements = getLastInBlock(returnBlock, predecessorMap);
               for(Statement lastStatement : lastStatements) {
                  previousStatements.add(new PreviousStatement(lastStatement, PreviousStatement.Type.LAST_IN_METHOD));
               }
            }
         }
      } else if(precedingStatements.size() > 0) {
         // The normal situation where the preceding statements are just normal statements executed before the current statement
         for(Statement precedingStatement : precedingStatements) {
            previousStatements.add(new PreviousStatement(precedingStatement, PreviousStatement.Type.NORMAL));
         }
      } else {
         // No preceding statements. Examine if this is the first statement in a call.
         Graph.Block block = statementInfos.getBlock(statement);
         if(getProgram().isProcedureEntry(block)) {
            // Current is first statement of a call - add the statement preceding the call.
            Collection<CallGraph.CallBlock.Call> callers = getProgram().getCallGraph().getCallers((ProcedureRef) block.getScope());
            for(CallGraph.CallBlock.Call call : callers) {
               Statement callStmt = statementInfos.getStatement(call.getCallStatementIdx());
               Collection<Statement> precedingCallStmt = getPrecedingStatement(callStmt, statementInfos, predecessorMap, prevInBlockByStmtIdx);
               for(Statement precedingCall : precedingCallStmt) {
                  previousStatements.add(new PreviousStatement(precedingCall, PreviousStatement.Type.BEFORE_METHOD));
               }
            }
         }
      }
      return previousStatements;
   }

   /**
    * Ensures that all defined vars have a non-zero live range
    *
    * @param liveRanges The live ranges
    * @param defined The variables to init live ranges for
    */
   private void initLiveRange(LiveRangeVariables liveRanges, Collection<VariableRef> defined,
                              Map<VariableRef, Integer> varIdMap, List<VariableRef> varsByIdList) {
      if(defined != null) {
         for(VariableRef variableRef : defined) {
            getOrAssignVarId(variableRef, varIdMap, varsByIdList);
            LiveRange lValLiveRange = liveRanges.getLiveRange(variableRef);
            if(lValLiveRange == null) {
               liveRanges.addEmptyAlive(variableRef);
               if(getProgram().getLog().isVerboseLiveRanges()) {
                  getProgram().getLog().append("Adding empty live range for unused variable " + variableRef);
               }
            }
         }
      }
   }

   /**
    * Gets all statements preceding a statement directly in the code.
    * This ignores calls and just returns the statement preceding the call.
    *
    * @param statement The statement to look for a preceding statement for
    * @return The preceding statement(s).
    * Multiple statements are returned if the current statement is the first in a block with multiple predecessor blocks.
    * Zero statements are returned if the current statement is the first statement in the program or the first statement in a method.
    */
   static Collection<Statement> getPrecedingStatement(Statement statement, Graph graph, StatementInfos statementInfos) {
      Statement previousStmt = null;
      Statement prev = null;
      Graph.Block block = statementInfos.getBlock(statement);
      for(Statement stmt : block.getStatements()) {
         if(statement.getIndex().equals(stmt.getIndex())) {
            previousStmt = prev;
            break;
         }
         prev = stmt;
      }
      if(previousStmt != null) {
         return Collections.singletonList(previousStmt);
      } else {
         List<Graph.Block> predecessors = graph.getPredecessors(block);
         ArrayList<Statement> last = new ArrayList<>();
         for(var predecessor : predecessors) {
            if(block.getLabel().equals(predecessor.getDefaultSuccessor()) || block.getLabel().equals(predecessor.getConditionalSuccessor())) {
               last.addAll(getLastInBlockFallback(predecessor, graph));
            }
         }
         return last;
      }
   }

   private static Collection<Statement> getLastInBlockFallback(Graph.Block block, Graph graph) {
      List<Statement> statements = block.getStatements();
      if(!statements.isEmpty()) {
         return Collections.singletonList(statements.get(statements.size() - 1));
      }
      List<Graph.Block> predecessors = graph.getPredecessors(block);
      ArrayList<Statement> last = new ArrayList<>();
      for(var predecessor : predecessors) {
         if(block.getLabel().equals(predecessor.getDefaultSuccessor()) || block.getLabel().equals(predecessor.getConditionalSuccessor())) {
            last.addAll(getLastInBlockFallback(predecessor, graph));
         }
      }
      return last;
   }

   static Collection<Statement> getPrecedingStatement(
         Statement statement,
         StatementInfos statementInfos,
         Map<LabelRef, List<Graph.Block>> predecessorMap,
         Statement[] prevInBlockByStmtIdx) {
      Statement previousStmt = prevInBlockByStmtIdx[statement.getIndex()];
      if(previousStmt != null) {
         return Collections.singletonList(previousStmt);
      } else {
         Graph.Block block = statementInfos.getBlock(statement);
         return getLastInPredecessors(block, predecessorMap);
      }
   }

   private static Collection<Statement> getLastInBlock(Graph.Block block, Map<LabelRef, List<Graph.Block>> predecessorMap) {
      List<Statement> statements = block.getStatements();
      if(statements.size() > 0) {
         return Collections.singletonList(statements.get(statements.size() - 1));
      } else {
         return getLastInPredecessors(block, predecessorMap);
      }
   }

   private static Collection<Statement> getLastInPredecessors(Graph.Block block, Map<LabelRef, List<Graph.Block>> predecessorMap) {
      List<Graph.Block> predecessors = predecessorMap.get(block.getLabel());
      if(predecessors == null || predecessors.isEmpty()) {
         return Collections.emptyList();
      }
      ArrayList<Statement> last = new ArrayList<>();
      for(var predecessor : predecessors) {
         if(block.getLabel().equals(predecessor.getDefaultSuccessor()) || block.getLabel().equals(predecessor.getConditionalSuccessor())) {
            last.addAll(getLastInBlock(predecessor, predecessorMap));
         }
      }
      return last;
   }

   /** A statement just before the current statement. */
   private static class PreviousStatement {

      /** The statement */
      private Statement statement;

      /** The type. */
      private Type type;

      PreviousStatement(Statement statement, Type type) {
         this.statement = statement;
         this.type = type;
      }

      public Statement getStatement() {
         return statement;
      }

      public int getStatementIdx() {
         return statement.getIndex();
      }

      public Type getType() {
         return type;
      }

      public enum Type {
         /**
          * The previous statement is executed immediately before the current statement.
          * It may be in the same block as the current statement or a preceding block if the current statement is the first in a block.
          */
         NORMAL,
         /** The previous statement is the last in a method. The current statement is the call to the method. */
         LAST_IN_METHOD,
         /**
          * The previous statement is the statement just before a method. The current statement is the first statement inside the method.
          * (Used for propagating variables used inside the method back to the calling context)
          */
         BEFORE_METHOD,
         /**
          * The previous statement is the statement before a call to a method, the current statement is the call
          * (used for propagating variables that are not not used by the called method to statements before the call)
          */
         SKIP_METHOD
      }

   }

}
