package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.CallGraph;
import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.values.ScopeRef;

import java.util.Collection;

/**
 * Automatically identifies recursive procedures and configures them with STACK_CALL convention
 * for re-entrant execution and stack-based local variable spilling.
 */
public class Pass1RecursionIdentification extends Pass1Base {

   public Pass1RecursionIdentification(Program program) {
      super(program);
   }

   @Override
   public boolean step() {
      CallGraph callGraph = getProgram().getCallGraph();
      Collection<Procedure> procedures = getProgramScope().getAllProcedures(true);
      boolean changed = false;
      for(Procedure procedure : procedures) {
         Collection<ScopeRef> recursiveCalls = callGraph.getRecursiveCalls(procedure.getRef());
         if(recursiveCalls.contains(procedure.getRef())) {
            if(procedure.isDeclaredInline()) {
               throw new CompileError("ERROR! Recursive procedure cannot be declared inline: " + procedure.getFullName());
            }
            if(procedure.getInterruptType() != null) {
               throw new CompileError("ERROR! Interrupt procedure cannot be recursive: " + procedure.getFullName());
            }
            if(Procedure.CallingConvention.PHI_CALL.equals(procedure.getCallingConvention())) {
               procedure.setCallingConvention(Procedure.CallingConvention.STACK_CALL);
               getLog().append("Inferred __stackcall for recursive procedure " + procedure.getFullName());
               changed = true;
            }
         }
      }
      return changed;
   }

}
