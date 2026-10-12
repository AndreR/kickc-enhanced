package dk.camelot64.kickc.passes.calcs;

import dk.camelot64.kickc.model.ControlFlowBlock;
import dk.camelot64.kickc.model.ControlFlowBlockSuccessorClosure;
import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementCallExecute;
import dk.camelot64.kickc.model.values.LabelRef;
import dk.camelot64.kickc.model.values.ProcedureRef;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** Calculates {@link dk.camelot64.kickc.model.ControlFlowBlockSuccessorClosure}. */
public class PassNCalcBlockSuccessorClosure extends PassNCalcBase<ControlFlowBlockSuccessorClosure> {

   public PassNCalcBlockSuccessorClosure(Program program) {
      super(program);
   }

   @Override
   public ControlFlowBlockSuccessorClosure calculate() {
      Map<LabelRef, Graph.Block> blockMap = new HashMap<>();
      for(var block : getProgram().getGraph().getAllBlocks()) {
         blockMap.put(block.getLabel(), block);
      }
      LinkedHashMap<LabelRef, Collection<LabelRef>> blockSuccessors = new LinkedHashMap<>();
      for(var block : getProgram().getGraph().getAllBlocks()) {
         LabelRef blockLabel = block.getLabel();
         LinkedHashSet<LabelRef> successorClosure = new LinkedHashSet<>();
         findSuccessorClosure(block.getLabel(), successorClosure, new java.util.HashSet<>(), blockMap);
         blockSuccessors.put(blockLabel, successorClosure);
      }
      return new ControlFlowBlockSuccessorClosure(blockSuccessors);
   }

   /**
    * Recursively get all blocks in the closure of successors & calls for a specific block
    *
    * @param labelRef The block to examine
    * @param successorClosure the set of all blocks that are successors (including called methods).
    * @param visited The blocks already visited during the search. Used to stop infinite recursion.
    */
   private void findSuccessorClosure(LabelRef labelRef, LinkedHashSet<LabelRef> successorClosure, java.util.Set<LabelRef> visited, Map<LabelRef, Graph.Block> blockMap) {
      if(labelRef == null || !visited.add(labelRef))
         return;
      Graph.Block block = blockMap.get(labelRef);
      if(block == null) {
         block = getProgram().getGraph().getBlock(labelRef);
         if(block == null)
            return;
         blockMap.put(labelRef, block);
      }
      successorClosure.add(labelRef);
      findSuccessorClosure(block.getDefaultSuccessor(), successorClosure, visited, blockMap);
      findSuccessorClosure(block.getConditionalSuccessor(), successorClosure, visited, blockMap);
      findSuccessorClosure(block.getCallSuccessor(), successorClosure, visited, blockMap);
      // Also handle stack-calls
      for(Statement statement : block.getStatements()) {
         if(statement instanceof StatementCallExecute) {
            final ProcedureRef calledProcRef = ((StatementCallExecute) statement).getProcedure();
            if(calledProcRef != null)
               findSuccessorClosure(calledProcRef.getLabelRef(), successorClosure, visited, blockMap);
         }
      }
   }

}
