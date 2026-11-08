package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.CallGraph;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.Registers;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementCalling;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Variable;

import java.util.Collection;
import java.util.List;

/**
 * Automatically assign 6502 hardware registers (A, X, Y) to parameters of leaf procedures.
 * Leaf procedures (functions with no outgoing calls, not interrupts, not stackcall) with up to 3 1-byte parameters
 * receive parameters directly in hardware registers:
 * - 1 parameter: Register A
 * - 2 parameters: Register A, Register X
 * - 3 parameters: Register A, Register X, Register Y
 *
 * This eliminates zero-page variable allocation and immediate stores at call sites.
 */
public class Pass1LeafFunctionRegisterCallingConvention extends Pass1Base {

   public Pass1LeafFunctionRegisterCallingConvention(Program program) {
      super(program);
   }

   @Override
   public boolean step() {
      CallGraph callGraph = getProgram().getCallGraph();
      Collection<Procedure> procedures = getProgramScope().getAllProcedures(true);

      for(Procedure procedure : procedures) {
         if(procedure.getInterruptType() != null || procedure.isDeclaredIntrinsic()) {
            continue;
         }
         String procName = procedure.getLocalName();
         if("main".equals(procName) || "__start".equals(procName)) {
            continue;
         }
         if(Procedure.CallingConvention.STACK_CALL.equals(procedure.getCallingConvention())) {
            continue;
         }

         // Check if procedure is a leaf procedure (no calls made from it in call graph)
         CallGraph.CallBlock callBlock = callGraph.getCallBlock(procedure.getRef());
         if(callBlock != null && callBlock.getCalls() != null && !callBlock.getCalls().isEmpty()) {
            continue;
         }

         // Double check statements in procedure blocks for any outgoing calls
         boolean hasCalls = false;
         for(var block : getGraph().getAllBlocks()) {
            if(procedure.getRef().equals(block.getScope())) {
               for(Statement stmt : block.getStatements()) {
                  if(stmt instanceof StatementCalling) {
                     hasCalls = true;
                     break;
                  }
               }
            }
            if(hasCalls) break;
         }
         if(hasCalls) {
            continue;
         }

         List<Variable> parameters = procedure.getParameters();
         if(parameters == null || parameters.isEmpty() || parameters.size() > 3) {
            continue;
         }

         // Check that all parameters qualify for 8-bit register passing
         boolean allParamsQualify = true;
         for(Variable param : parameters) {
            if(param.getType() == null || param.getType().getSizeBytes() != 1) {
               allParamsQualify = false;
               break;
            }
            if(param.isStruct() || param.isArray() || param.isVolatile() || param.isKindConstant() || param.isKindLoadStore()) {
               allParamsQualify = false;
               break;
            }
            if(param.getRegister() != null || param.getMemoryAddress() != null) {
               allParamsQualify = false;
               break;
            }
         }
         if(!allParamsQualify) {
            continue;
         }

         // Assign hardware registers: 1 param -> A; 2 params -> A, X; 3 params -> A, X, Y
         Registers.Register[] leafRegisters = new Registers.Register[] {
            Registers.getRegisterA(),
            Registers.getRegisterX(),
            Registers.getRegisterY()
         };

         for(int i = 0; i < parameters.size(); i++) {
            Variable param = parameters.get(i);
            Registers.Register reg = leafRegisters[i];
            param.setRegister(reg);
            getLog().append("Assigned register " + reg.toString() + " to parameter " + param.getLocalName() + " of leaf procedure " + procName);
         }
      }
      return false;
   }
}
