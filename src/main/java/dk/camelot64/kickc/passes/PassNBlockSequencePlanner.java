package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.values.LabelRef;

import java.util.*;

/** Plan the optimal sequence for the blocks of the control flow graph */
public class PassNBlockSequencePlanner extends Pass2SsaOptimization {

   Deque<ScopeTodo> todoScopes = new ArrayDeque<>();
   private final Map<LabelRef, Graph.Block> blockCache = new HashMap<>();
   private final Map<LabelRef, Set<LabelRef>> reachableMap = new HashMap<>();

   public PassNBlockSequencePlanner(Program program) {
      super(program);
   }

   @Override
   public boolean step() {

      blockCache.clear();
      reachableMap.clear();
      for(Graph.Block b : getGraph().getAllBlocks()) {
         blockCache.put(b.getLabel(), b);
      }

      List<Graph.Block> entryPointBlocks = getProgram().getEntryPointBlocks();

      for(var entryPointBlock : entryPointBlocks) {
         pushTodo(entryPointBlock);
      }

      List<LabelRef> sequence = new ArrayList<>();
      Set<LabelRef> visited = new HashSet<>();
      while(hasTodo()) {
         Graph.Block block = popTodo();
         if(block == null) {
            break;
         }
         if(!visited.add(block.getLabel())) {
            // already handled - move on
            continue;
         }
         sequence.add(block.getLabel());
         if(block.getCallSuccessor() != null) {
            pushCallTodo(blockCache.get(block.getCallSuccessor()));
         }
         Graph.Block conditionalSuccessor = getConditionalSuccessorFast(block);
         Graph.Block defaultSuccessor = getDefaultSuccessorFast(block);
         if(conditionalSuccessor != null && defaultSuccessor != null) {
            // Both conditional and default successor
            boolean condFirst = true;
            boolean condReachesDef = reachesFast(conditionalSuccessor, defaultSuccessor.getLabel());
            boolean defReachesCond = reachesFast(defaultSuccessor, conditionalSuccessor.getLabel());
            if(defReachesCond && !condReachesDef) {
               // defaultSuccessor flows into conditionalSuccessor (e.g. negated branch to loop exit)
               condFirst = false;
            }

            if(condFirst) {
               // Best sequence is cond->default (resulting in better code locality)
               pushTodo(defaultSuccessor);
               pushTodo(conditionalSuccessor);
            } else {
               // Best sequence is default->cond
               pushTodo(conditionalSuccessor);
               pushTodo(defaultSuccessor);
            }
         } else if(conditionalSuccessor != null) {
            // Only a conditional successor
            pushTodo(conditionalSuccessor);
         } else if(defaultSuccessor != null) {
            // Only a default successor
            pushTodo(defaultSuccessor);
         }

      }
      setSequence(sequence);

      if(getLog().isVerboseSequencePlan()) {
         StringBuilder entry = new StringBuilder();
         entry.append("Block Sequence Planned ");
         for(LabelRef labelRef : sequence) {
            entry.append(labelRef.getFullName() + " ");

         }
         getLog().append(entry.toString());
      }

      return false;

   }

   public void setSequence(List<LabelRef> sequence) {
      if(sequence.size() != getGraph().getAllBlocks().size()) {
         throw new CompileError("ERROR! Sequence does not contain all blocks from the program. Sequence: " + sequence.size() + " Blocks: " + getGraph().getAllBlocks().size());
      }

      // First re-order the procedureCompilations and group blocks in a single O(B) pass
      Set<ProcedureCompilation> updatedProgramSet = new LinkedHashSet<>();
      Map<ProcedureCompilation, List<Graph.Block>> procBlocks = new LinkedHashMap<>();
      for(LabelRef labelRef : sequence) {
         final Procedure procedure = getProgramScope().getSymbol(labelRef).getContainingProcedure();
         final ProcedureCompilation procedureCompilation = getProgram().getProcedureCompilation(procedure.getRef());
         updatedProgramSet.add(procedureCompilation);
         procBlocks.computeIfAbsent(procedureCompilation, k -> new ArrayList<>())
               .add(procedureCompilation.getGraph().getBlock(labelRef));
      }
      List<ProcedureCompilation> updatedProgramSequence = new ArrayList<>(updatedProgramSet);
      getProgram().setProcedureCompilations(updatedProgramSequence);

      // Now re-order the blocks for each procedure
      for(var procedureCompilation : updatedProgramSequence) {
         final ControlFlowGraph procedureGraph = procedureCompilation.getGraph();
         final List<Graph.Block> updatedProcedureSequence = procBlocks.getOrDefault(procedureCompilation, Collections.emptyList());
         if(updatedProcedureSequence.size() != procedureGraph.getAllBlocks().size()) {
            throw new CompileError("ERROR! Sequence does not contain all blocks for "+procedureCompilation.getProcedureRef()+". Sequence: " + updatedProcedureSequence.size() + " Blocks: " + procedureGraph.getAllBlocks().size());
         }
         procedureCompilation.setGraph(new ControlFlowGraph(updatedProcedureSequence));
      }

   }

   void pushTodo(Graph.Block block) {
      LabelRef blockRef = block.getLabel();
      Scope blockScope = getProgramScope().getSymbol(blockRef).getScope();
      for(ScopeTodo todoScope : todoScopes) {
         if(todoScope.scope.equals(blockScope)) {
            todoScope.pushTodo(block);
            return;
         }
      }
      ScopeTodo newScopeTodo = new ScopeTodo(blockScope);
      todoScopes.push(newScopeTodo);
      newScopeTodo.pushTodo(block);
   }

   void pushCallTodo(Graph.Block block) {
      LabelRef blockRef = block.getLabel();
      Scope blockScope = getProgramScope().getSymbol(blockRef).getScope();
      for(ScopeTodo todoScope : todoScopes) {
         if(todoScope.scope.equals(blockScope)) {
            todoScope.addTodo(block);
            return;
         }
      }
      ScopeTodo newScopeTodo = new ScopeTodo(blockScope);
      ScopeTodo top = null;
      if(todoScopes.size() > 0)
         top = todoScopes.pop();
      todoScopes.push(newScopeTodo);
      newScopeTodo.pushTodo(block);
      if(top != null)
         todoScopes.push(top);
   }

   boolean hasTodo() {
      return !todoScopes.isEmpty();
   }

   Graph.Block popTodo() {
      ScopeTodo scopeTodo = todoScopes.peek();
      if(scopeTodo == null || scopeTodo.todo.isEmpty()) {
         if(scopeTodo != null) todoScopes.pop();
         return null;
      }
      int size = scopeTodo.todo.size();
      if(size == 1) {
         Graph.Block block = scopeTodo.todo.remove(0);
         todoScopes.pop();
         return block;
      }
      // Topological selection: find a block that is NOT reached by any other pending block
      int bestIdx = -1;
      for(int i = 0; i < size; i++) {
         Graph.Block candidate = scopeTodo.todo.get(i);
         LabelRef candLabel = candidate.getLabel();
         boolean reachedByOther = false;
         for(int j = 0; j < size; j++) {
            if(i == j) continue;
            Graph.Block other = scopeTodo.todo.get(j);
            if(!candLabel.equals(other.getLabel()) && reachesFast(other, candLabel)) {
               reachedByOther = true;
               break;
            }
         }
         if(!reachedByOther) {
            bestIdx = i;
            break;
         }
      }
      if(bestIdx == -1) {
         bestIdx = 0;
      }
      Graph.Block bestBlock = scopeTodo.todo.remove(bestIdx);
      if(scopeTodo.todo.isEmpty()) {
         todoScopes.pop();
      }
      return bestBlock;
   }

   private Graph.Block getDefaultSuccessorFast(Graph.Block block) {
      if(block == null || block.getDefaultSuccessor() == null) return null;
      return blockCache.get(block.getDefaultSuccessor());
   }

   private Graph.Block getConditionalSuccessorFast(Graph.Block block) {
      if(block == null || block.getConditionalSuccessor() == null) return null;
      return blockCache.get(block.getConditionalSuccessor());
   }

   private boolean reachesFast(Graph.Block from, LabelRef target) {
      if(from == null) return false;
      if(target.equals(from.getLabel())) return true;
      Set<LabelRef> reach = reachableMap.get(from.getLabel());
      if(reach != null) {
         return reach.contains(target);
      }
      reach = new HashSet<>();
      reach.add(from.getLabel());
      Deque<Graph.Block> work = new ArrayDeque<>();
      work.add(from);
      while(!work.isEmpty()) {
         Graph.Block curr = work.pop();
         Graph.Block defSucc = getDefaultSuccessorFast(curr);
         if(defSucc != null) {
            boolean added = reach.add(defSucc.getLabel());
            Set<LabelRef> cached = reachableMap.get(defSucc.getLabel());
            if(cached != null) {
               reach.addAll(cached);
            } else if(added) {
               work.push(defSucc);
            }
         }
         Graph.Block condSucc = getConditionalSuccessorFast(curr);
         if(condSucc != null) {
            boolean added = reach.add(condSucc.getLabel());
            Set<LabelRef> cached = reachableMap.get(condSucc.getLabel());
            if(cached != null) {
               reach.addAll(cached);
            } else if(added) {
               work.push(condSucc);
            }
         }
      }
      reachableMap.put(from.getLabel(), reach);
      return reach.contains(target);
   }

   private static class ScopeTodo {

      Scope scope;

      List<Graph.Block> todo;

      public ScopeTodo(Scope scope) {
         this.scope = scope;
         this.todo = new ArrayList<>();
      }

      public void pushTodo(Graph.Block block) {
         todo.add(0, block);
      }

      public void addTodo(Graph.Block block) {
         todo.add(block);
      }

   }

}
