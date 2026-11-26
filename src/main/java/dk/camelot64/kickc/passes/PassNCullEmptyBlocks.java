package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.statements.StatementPhiBlock;
import dk.camelot64.kickc.model.symbols.Label;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.values.LabelRef;
import dk.camelot64.kickc.model.values.RValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pass that culls empty control flow blocks from the program */
public class PassNCullEmptyBlocks extends Pass2SsaOptimization {

   private boolean pass1;

   public PassNCullEmptyBlocks(Program program, boolean pass1) {
      super(program);
      this.pass1 = pass1;
   }

   @Override
   public boolean step() {
      final List<Graph.Block> allBlocks = getGraph().getAllBlocks();
      final Map<LabelRef, Graph.Block> blockMap = new HashMap<>(allBlocks.size() * 2);
      final Map<LabelRef, List<Graph.Block>> predMap = new HashMap<>(allBlocks.size() * 2);
      final List<Graph.Block> remove = new ArrayList<>();
      for(var block : allBlocks) {
         blockMap.put(block.getLabel(), block);
         if(block.getDefaultSuccessor() != null) {
            predMap.computeIfAbsent(block.getDefaultSuccessor(), k -> new ArrayList<>()).add(block);
         }
         if(block.getConditionalSuccessor() != null) {
            predMap.computeIfAbsent(block.getConditionalSuccessor(), k -> new ArrayList<>()).add(block);
         }
         if(block.getCallSuccessor() != null) {
            predMap.computeIfAbsent(block.getCallSuccessor(), k -> new ArrayList<>()).add(block);
         }
         if(block.getStatements().isEmpty() && block.getLabel().isIntermediate()) {
            remove.add(block);
         }
      }
      if(remove.isEmpty()) {
         return false;
      }

      List<Graph.Block> dontRemove = new ArrayList<>();

      for(final Graph.Block removeBlock : remove) {
         Graph.Block successor = removeBlock.getDefaultSuccessor() != null ? blockMap.get(removeBlock.getDefaultSuccessor()) : null;
         if(successor==null) {
            dontRemove.add(removeBlock);
            continue;
         }
         LabelRef successorRef = successor.getLabel();
         LabelRef removeBlockLabelRef = removeBlock.getLabel();
         // Replace all jumps (default/conditional/call) to @removeBlock with a jump to the default successor
         List<Graph.Block> rawPreds = predMap.get(removeBlockLabelRef);
         final List<Graph.Block> predecessors;
         if(rawPreds == null || rawPreds.isEmpty()) {
            predecessors = Collections.emptyList();
         } else {
            predecessors = new ArrayList<>(rawPreds);
            predecessors.sort(Comparator.comparing(o -> o.getLabel().getFullName()));
         }

         // If a candidate remove block has a predecessor that has the same successor as the remove block:
         // Do not remove it - because this will result in problems in distinguishing the default successor and
         // the conditional successor when generating the phi-block of the successor
         boolean dontCull = false;
         for(var predecessor : predecessors) {
            if(successorRef.equals(predecessor.getConditionalSuccessor()) || successorRef.equals(predecessor.getDefaultSuccessor())) {
               if(getLog().isVerboseNonOptimization()) {
                  getLog().append("Not culling empty block because it shares successor with its predecessor. " + removeBlockLabelRef.toString(getProgram()));
               }
               dontCull = true;
               dontRemove.add(removeBlock);
            }
         }
         if(dontCull)
            continue;

         // In all phi functions of a successor blocks make a copy of the phi assignment for each predecessor
         GraphBaseVisitor<Void> phiFixVisitor = new GraphBaseVisitor<Void>() {
            @Override
            public Void visitPhiBlock(StatementPhiBlock phi) {
               for(StatementPhiBlock.PhiVariable phiVariable : phi.getPhiVariables()) {
                  for(StatementPhiBlock.PhiRValue phiRValue : phiVariable.getValues()) {
                     if(phiRValue.getPredecessor().equals(removeBlockLabelRef)) {
                        // Found a phi function referencing the remove block - add copies for each predecessor
                        RValue previousRValue = phiRValue.getrValue();
                        for(var predecessor : predecessors) {
                           if(phiRValue != null) {
                              phiRValue.setPredecessor(predecessor.getLabel());
                              phiRValue = null;
                           } else {
                              phiVariable.setrValue(predecessor.getLabel(), previousRValue);
                           }
                        }
                        break;
                     }
                  }
               }
               return null;
            }
         };
         phiFixVisitor.visitBlock(successor);

         List<Graph.Block> succPreds = predMap.computeIfAbsent(successorRef, k -> new ArrayList<>());
         for(var predecessor : predecessors) {
            Map<LabelRef, LabelRef> replace = new LinkedHashMap<>();
            replace.put(removeBlockLabelRef, successorRef);
            if(removeBlockLabelRef.equals(predecessor.getDefaultSuccessor())) {
               predecessor.setDefaultSuccessor(successorRef);
               succPreds.add(predecessor);
            }
            if(removeBlockLabelRef.equals(predecessor.getConditionalSuccessor())) {
               predecessor.setConditionalSuccessor(successorRef);
               succPreds.add(predecessor);
            }
            if(removeBlockLabelRef.equals(predecessor.getCallSuccessor())) {
               predecessor.setCallSuccessor(successorRef);
               succPreds.add(predecessor);
            }
            replaceLabels(predecessor, replace);
         }

         if(removeBlock.getDefaultSuccessor() != null) {
            List<Graph.Block> list = predMap.get(removeBlock.getDefaultSuccessor());
            if(list != null) list.removeIf(b -> b.getLabel().equals(removeBlockLabelRef));
         }
         if(removeBlock.getConditionalSuccessor() != null) {
            List<Graph.Block> list = predMap.get(removeBlock.getConditionalSuccessor());
            if(list != null) list.removeIf(b -> b.getLabel().equals(removeBlockLabelRef));
         }
         if(removeBlock.getCallSuccessor() != null) {
            List<Graph.Block> list = predMap.get(removeBlock.getCallSuccessor());
            if(list != null) list.removeIf(b -> b.getLabel().equals(removeBlockLabelRef));
         }
         predMap.remove(removeBlockLabelRef);
         blockMap.remove(removeBlockLabelRef);

         Label removeBlockLabel = getProgramScope().getLabel(removeBlockLabelRef);
         final Scope removeBlockScope = removeBlockLabel.getScope();
         final Procedure procedure = removeBlockScope.getContainingProcedure();
         final ProcedureCompilation procedureCompilation = getProgram().getProcedureCompilation(procedure.getRef());
         procedureCompilation.getGraph().remove(removeBlockLabelRef);

         removeBlockScope.remove(removeBlockLabel);
         if(!pass1)
            getLog().append("Culled Empty Block " + removeBlockLabel.toString(getProgram()));
      }
      remove.removeAll(dontRemove);
      return remove.size() > 0;
   }

}
