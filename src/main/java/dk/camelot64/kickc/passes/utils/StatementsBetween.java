package dk.camelot64.kickc.passes.utils;

import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.values.LabelRef;
import dk.camelot64.kickc.model.values.ProcedureRef;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class StatementsBetween {
   /**
    * Get all statements executed between two statements (none of these are included in the result)
    *
    * @param from The statement to start at
    * @param to The statement to end at
    * @return All statements executed between the two passed statements
    */
   public static Collection<Statement> find(Statement from, Graph.Block fromBlock, Statement to, Graph graph) {
      if(fromBlock != null) {
         List<Statement> stmts = fromBlock.getStatements();
         int fromIdx = stmts.indexOf(from);
         if(fromIdx >= 0) {
            if(fromIdx + 1 < stmts.size() && stmts.get(fromIdx + 1).equals(to)) {
               return Collections.emptyList();
            }
            for(int i = fromIdx + 1; i < stmts.size(); i++) {
               if(stmts.get(i).equals(to)) {
                  return new ArrayList<>(stmts.subList(fromIdx + 1, i));
               }
            }
         }
      }
      Collection<Statement> between = new LinkedHashSet<>();
      Set<LabelRef> visited = new HashSet<>();
      populateStatementsBetween(fromBlock, from, to, false, between, graph, visited);
      return between;
   }

   /**
    * Fill the between collection with all statements executed between two statements (none of these are included in the result)
    *
    * @param block The block to start from
    * @param from The statement where we start counting (not included)
    * @param to The statement to end at (not included)
    * @param isBetween Are we already between from and to?
    * @param between The between collection, being populated
    */
   private static void populateStatementsBetween(Graph.Block block, Statement from, Statement to, boolean isBetween, Collection<Statement> between, Graph graph, Set<LabelRef> visited) {
      if(block == null) return;
      if(isBetween) {
         if(visited.contains(block.getLabel()))
            return;
         visited.add(block.getLabel());
      }
      for(Statement statement : block.getStatements()) {
         if(between.contains(statement))
            // Stop infinite recursion
            return;
         if(isBetween) {
            if(statement.equals(to))
               // The end was reached!
               isBetween = false;
            else
               // We are between - add the statement
               between.add(statement);
         } else {
            if(statement.equals(from))
               // We are now between!
               isBetween = true;
         }
      }
      if(isBetween) {
         // Recurse to successor blocks
         final Collection<LabelRef> successors = block.getSuccessors();
         for(LabelRef successor : successors) {
            if(ProcedureRef.PROCEXIT_BLOCK_NAME.equals(successor.getLocalName()))
               continue;
            final Graph.Block successorBlock = graph.getBlock(successor);
            if(successorBlock == null || !Objects.equals(block.getScope(), successorBlock.getScope()))
               continue;
            populateStatementsBetween(successorBlock, from, to, true, between, graph, visited);
         }
      }
   }
}
