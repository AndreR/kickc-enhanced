package dk.camelot64.kickc.passes.calcs;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.values.LabelRef;
import dk.camelot64.kickc.model.values.ScopeRef;

import java.util.*;
import java.util.stream.Collectors;

/** Finds the dominators for the control flow graph. */
public class PassNCalcDominators extends PassNCalcBase<DominatorsGraph> {

   public PassNCalcDominators(Program program) {
      super(program);
   }

   /**
    * Analyse the control flow graph to find dominators for all blocks.
    * <p>
    * Definition: d dom i if all paths from entry to node i include d
    * <p>
    * See http://www.cs.colostate.edu/~cs553/ClassNotes/lecture09-control-dominators.ppt.pdf
    */
   @Override
   public DominatorsGraph calculate() {
      DominatorsGraph dominatorsGraph = new DominatorsGraph();

      // Pre-index blocks, predecessors and scope blocks in a single O(N) pass
      Collection<Graph.Block> allBlocks = getGraph().getAllBlocks();
      Map<LabelRef, Graph.Block> blockMap = new HashMap<>(allBlocks.size() * 2);
      Map<LabelRef, List<Graph.Block>> predecessorMap = new HashMap<>(allBlocks.size() * 2);
      Map<ScopeRef, List<Graph.Block>> scopeBlocksMap = new HashMap<>();

      for(Graph.Block b : allBlocks) {
         blockMap.put(b.getLabel(), b);
         if(b.getScope() != null) {
            scopeBlocksMap.computeIfAbsent(b.getScope(), k -> new ArrayList<>()).add(b);
         }
         if(b.getDefaultSuccessor() != null) {
            predecessorMap.computeIfAbsent(b.getDefaultSuccessor(), k -> new ArrayList<>()).add(b);
         }
         if(b.getConditionalSuccessor() != null) {
            predecessorMap.computeIfAbsent(b.getConditionalSuccessor(), k -> new ArrayList<>()).add(b);
         }
         if(b.getCallSuccessor() != null) {
            predecessorMap.computeIfAbsent(b.getCallSuccessor(), k -> new ArrayList<>()).add(b);
         }
      }

      Collection<Procedure> procedures = getScope().getAllProcedures(true);

      // Parallel calculation across all available CPU cores
      List<Map<LabelRef, DominatorsBlock>> procDominatorMaps = procedures.parallelStream()
            .map(procedure -> calculateProcedureDominators(procedure, scopeBlocksMap, blockMap, predecessorMap))
            .collect(Collectors.toList());

      for(Map<LabelRef, DominatorsBlock> procMap : procDominatorMaps) {
         dominatorsGraph.getBlockDominators().putAll(procMap);
      }

      return dominatorsGraph;
   }

   private Map<LabelRef, DominatorsBlock> calculateProcedureDominators(
         Procedure procedure,
         Map<ScopeRef, List<Graph.Block>> scopeBlocksMap,
         Map<LabelRef, Graph.Block> blockMap,
         Map<LabelRef, List<Graph.Block>> predecessorMap) {

      Map<LabelRef, DominatorsBlock> dominatorsMap = new HashMap<>();

      // Initialize dominators: Dom[first]={first}, Dom[block]={all}
      List<LabelRef> firstBlocks = new ArrayList<>();
      LabelRef firstBlock = procedure.getRef().getLabelRef();
      DominatorsBlock firstDominators = new DominatorsBlock();
      firstDominators.add(firstBlock);
      dominatorsMap.put(firstBlock, firstDominators);
      firstBlocks.add(firstBlock);

      List<Graph.Block> scopeBlocks = scopeBlocksMap.get(procedure.getRef());
      if(scopeBlocks == null || scopeBlocks.isEmpty()) {
         return dominatorsMap;
      }

      List<LabelRef> procedureBlocks = new ArrayList<>(scopeBlocks.size());
      for(Graph.Block b : scopeBlocks) {
         procedureBlocks.add(b.getLabel());
      }

      for(LabelRef procedureBlock : procedureBlocks) {
         if(!firstBlocks.contains(procedureBlock)) {
            DominatorsBlock dominatorsBlock = new DominatorsBlock();
            dominatorsBlock.addAll(procedureBlocks);
            dominatorsMap.put(procedureBlock, dominatorsBlock);
         }
      }

      // Iteratively refine dominators until they do not change
      // For all nodes:
      // Dom[n] = {n} UNION ( INTERSECT Dom[p] for all p that are predecessors of n)
      boolean change;
      do {
         change = false;
         for(LabelRef procedureBlock : procedureBlocks) {
            if(!firstBlocks.contains(procedureBlock)) {
               Graph.Block block = blockMap.get(procedureBlock);
               List<Graph.Block> predecessors = block != null ? predecessorMap.get(block.getLabel()) : null;
               DominatorsBlock newDominators = new DominatorsBlock();
               if(predecessors != null && !predecessors.isEmpty()) {
                  var predIt = predecessors.iterator();
                  DominatorsBlock firstPredDom = dominatorsMap.get(predIt.next().getLabel());
                  if(firstPredDom != null) {
                     newDominators.addAll(firstPredDom.getDominators());
                  }
                  while(predIt.hasNext()) {
                     DominatorsBlock otherPredDom = dominatorsMap.get(predIt.next().getLabel());
                     if(otherPredDom != null) {
                        newDominators.intersect(otherPredDom);
                     }
                  }
               }
               newDominators.add(procedureBlock);
               DominatorsBlock currentDominators = dominatorsMap.get(procedureBlock);
               if(currentDominators != null && !currentDominators.equals(newDominators)) {
                  change = true;
                  dominatorsMap.put(procedureBlock, newDominators);
               }
            }
         }
      } while(change);

      return dominatorsMap;
   }

}
