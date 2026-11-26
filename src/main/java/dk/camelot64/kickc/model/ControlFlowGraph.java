package dk.camelot64.kickc.model;

import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.values.LabelRef;

import java.util.*;

/**
 * The control flow graph of the program.
 * The control flow  graph is a set of connected basic blocks.
 */
public class ControlFlowGraph implements Graph {

   private List<Graph.Block> blocks;
   private volatile BlockIndex blockIndex;

   /**
    * Sequence of blocks used when generating ASM
    */
   private List<LabelRef> sequence;

   private static final class BlockIndex {
      final Map<LabelRef, Graph.Block> map;
      final LabelRef[] labels;

      BlockIndex(List<Graph.Block> blocks) {
         int size = blocks.size();
         Map<LabelRef, Graph.Block> m = new HashMap<>(Math.max(4, size * 2));
         LabelRef[] l = new LabelRef[size];
         int i = 0;
         for(Graph.Block block : blocks) {
            LabelRef label = block.getLabel();
            l[i++] = label;
            m.put(label, block);
         }
         this.map = m;
         this.labels = l;
      }

      boolean isConsistent(List<Graph.Block> blocks) {
         if(blocks.size() != labels.length) {
            return false;
         }
         int i = 0;
         for(Graph.Block block : blocks) {
            if(!Objects.equals(block.getLabel(), labels[i++])) {
               return false;
            }
         }
         return true;
      }
   }

   public ControlFlowGraph(List<Graph.Block> blocks) {
      this.blocks = blocks;
      this.blockIndex = new BlockIndex(blocks);
   }

   public Graph.Block getBlock(LabelRef symbol) {
      if(symbol == null) {
         return null;
      }
      BlockIndex current = this.blockIndex;
      if(current != null) {
         Graph.Block block = current.map.get(symbol);
         if(block != null) {
            if(Objects.equals(block.getLabel(), symbol)) {
               return block;
            }
         } else if(current.isConsistent(blocks)) {
            return null;
         }
      }
      synchronized(this) {
         current = this.blockIndex;
         if(current != null) {
            Graph.Block block = current.map.get(symbol);
            if(block != null) {
               if(Objects.equals(block.getLabel(), symbol)) {
                  return block;
               }
            } else if(current.isConsistent(blocks)) {
               return null;
            }
         }
         BlockIndex rebuilt = new BlockIndex(blocks);
         this.blockIndex = rebuilt;
         return rebuilt.map.get(symbol);
      }
   }

   public void invalidateBlockMap() {
      this.blockIndex = null;
   }

   public void addBlock(Graph.Block block) {
      blocks.add(block);
      this.blockIndex = null;
   }

   public List<Block> getAllBlocks() {
      return Collections.unmodifiableList(blocks);
   }

   public void remove(LabelRef label) {
      this.blockIndex = null;
      ListIterator<Graph.Block> blocksIt = blocks.listIterator();
      while(blocksIt.hasNext()) {
         var block = blocksIt.next();
         if(block.getLabel().equals(label)) {
            blocksIt.remove();
            return;
         }
      }
   }

   @Override
   public String toString() {
      return toString(null);
   }


   @Override
   public boolean equals(Object o) {
      if(this == o) return true;
      if(o == null || getClass() != o.getClass()) return false;
      ControlFlowGraph that = (ControlFlowGraph) o;
      return Objects.equals(blocks, that.blocks) &&
            Objects.equals(sequence, that.sequence);
   }

   @Override
   public int hashCode() {
      return Objects.hash(blocks, sequence);
   }

   /**
    * Clear all statement indices,
    */
   public void clearStatementIndices() {
      for (Statement statement : getAllStatements()) {
         statement.setIndex(null);
      }
   }

}
