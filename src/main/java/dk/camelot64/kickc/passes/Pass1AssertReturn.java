package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.ControlFlowGraph;
import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.ProcedureCompilation;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.statements.StatementConditionalJump;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.values.LabelRef;
import dk.camelot64.kickc.model.values.RValue;
import dk.camelot64.kickc.model.values.StructMemberRef;
import dk.camelot64.kickc.model.values.SymbolRef;
import dk.camelot64.kickc.model.values.VariableRef;
import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * Assert that all non-void methods end with a return statement
 */
public class Pass1AssertReturn extends Pass1Base {

   public Pass1AssertReturn(Program program) {
      super(program);
   }

   @Override
   public boolean step() {
      Collection<Procedure> allProcedures = getProgram().getScope().getAllProcedures(true);
      for(Procedure procedure : allProcedures) {
         if(procedure.isDeclaredIntrinsic()) continue;
         if(procedure.getReturnType() != null && !SymbolType.VOID.equals(procedure.getReturnType())) {
            final ProcedureCompilation procedureCompilation = getProgram().getProcedureCompilation(procedure.getRef());
            final ControlFlowGraph graph = procedureCompilation.getGraph();
            LabelRef entryLabel = procedure.getRef().getLabelRef();
            Graph.Block entryBlock = graph.getBlock(entryLabel);
            assertReturn(graph, entryBlock, new LinkedHashSet<>());
         }
      }
      return false;
   }

   /**
    * Assert that all control flows end at a return statement.
    * Follow the control flow of the graph recursively.
    *
    * @param block The block to examine
    * @param visited Blocks already visited
    */
   public void assertReturn(ControlFlowGraph graph, Graph.Block block, Collection<LabelRef> visited) {
      if(visited.contains(block.getLabel())) {
         return;
      }
      visited.add(block.getLabel());
      for(Statement statement : block.getStatements()) {
         if(statement instanceof StatementAssignment assignment) {
            RValue target = assignment.getlValue();
            while(target instanceof StructMemberRef) {
               target = ((StructMemberRef) target).getStruct();
            }
            if(target instanceof VariableRef && ((VariableRef) target).getLocalName().equals("return")) {
               // Happy days - return found!
               return;
            }
         } else if(statement instanceof StatementConditionalJump cond) {
            Graph.Block jumpTo = graph.getBlock(cond.getDestination());
            assertReturn(graph, jumpTo, visited);
         }
      }
      Graph.Block successor = graph.getBlock(block.getDefaultSuccessor());
      if(successor == null || successor.getLabel().getLocalName().equals(SymbolRef.PROCEXIT_BLOCK_NAME)) {
         throw new CompileError("Error! Method must end with a return statement. " + block.getScope().toString(getProgram()));
      } else {
         assertReturn(graph, successor, visited);
      }
   }

}
