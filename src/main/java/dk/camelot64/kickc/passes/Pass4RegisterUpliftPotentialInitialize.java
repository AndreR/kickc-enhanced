package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.values.VariableRef;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/*** Initialize potential registers for each equivalence class.
 *
 * */
public class Pass4RegisterUpliftPotentialInitialize extends Pass2Base {

   public Pass4RegisterUpliftPotentialInitialize(Program program) {
      super(program);
   }

   /***
    * Initialize register potentials for each equivalence class with all potential registers
    */
   public void initPotentialRegisters() {
      LiveRangeEquivalenceClassSet liveRangeEquivalenceClassSet = getProgram().getLiveRangeEquivalenceClassSet();
      RegisterPotentials registerPotentials = new RegisterPotentials();
      for(LiveRangeEquivalenceClass equivalenceClass : liveRangeEquivalenceClassSet.getEquivalenceClasses()) {
         Registers.Register declaredRegister = null;
         int bytes = -1;
         for(VariableRef varRef : equivalenceClass.getVariables()) {
            Variable variable = getProgram().getScope().getVariable(varRef);
            if(variable.getRegister() != null) {
               if(declaredRegister != null && !declaredRegister.equals(variable.getRegister())) {
                  throw new CompileError("Equivalence class has variables with different declared registers \n" +
                        " - equivalence class: " + equivalenceClass.toString(true) + "\n" +
                        " - one register: " + declaredRegister.toString() + "\n" +
                        " - other register: " + variable.getRegister().toString()
                  );
               }
               declaredRegister = variable.getRegister();
               bytes = variable.getType().getSizeBytes();
            }
         }
         if(declaredRegister != null) {
            if(declaredRegister instanceof Registers.RegisterZpMem) {
               int zp = ((Registers.RegisterZpMem) declaredRegister).getZp();
               Registers.RegisterZpMem zpRegister = new Registers.RegisterZpMem(zp, bytes, true);
               registerPotentials.setPotentialRegisters(equivalenceClass, Arrays.asList(zpRegister));
            } else if(declaredRegister instanceof Registers.RegisterMainMem) {
               VariableRef varRef = ((Registers.RegisterMainMem) declaredRegister).getVariableRef();
               Long address = ((Registers.RegisterMainMem) declaredRegister).getAddress();
               Registers.RegisterMainMem memRegister = new Registers.RegisterMainMem(varRef, bytes, address, true);
               registerPotentials.setPotentialRegisters(equivalenceClass, Arrays.asList(memRegister));
            } else {
               registerPotentials.setPotentialRegisters(equivalenceClass, Arrays.asList(declaredRegister));
            }
         } else {
            Registers.Register defaultRegister = equivalenceClass.getRegister();
            List<Registers.Register> potentials = new ArrayList<>();
            boolean isByte2 = defaultRegister.isMem() && defaultRegister.getBytes() == 1;
            if(isByte2 && !varVolatile(equivalenceClass)) {
               Integer leafParamIdx = getLeafProcedureParameterIndex(equivalenceClass);
               if(leafParamIdx != null) {
                  if(leafParamIdx == 0) {
                     potentials.add(Registers.getRegisterA());
                     potentials.add(Registers.getRegisterX());
                     potentials.add(defaultRegister);
                     potentials.add(Registers.getRegisterY());
                  } else if(leafParamIdx == 1) {
                     potentials.add(Registers.getRegisterX());
                     potentials.add(Registers.getRegisterA());
                     potentials.add(defaultRegister);
                     potentials.add(Registers.getRegisterY());
                  } else {
                     potentials.add(Registers.getRegisterY());
                     potentials.add(defaultRegister);
                     potentials.add(Registers.getRegisterX());
                  }
               } else {
                  potentials.add(defaultRegister);
                  potentials.add(Registers.getRegisterA());
                  potentials.add(Registers.getRegisterX());
                  potentials.add(Registers.getRegisterY());
               }
               if(getProgram().getTargetCpu().getCpu65xx().hasRegisterZ())
                  potentials.add(Registers.getRegisterZ());
            } else {
               potentials.add(defaultRegister);
            }
            registerPotentials.setPotentialRegisters(equivalenceClass, potentials);
         }
      }
      getProgram().setRegisterPotentials(registerPotentials);
   }

   /**
    * Determine if any variable is declared as volatile
    *
    * @param equivalenceClass The variable equivalence class
    * @return true if any variable is volatile
    */
   
   private Integer getLeafProcedureParameterIndex(LiveRangeEquivalenceClass equivalenceClass) {
      CallGraph callGraph = getProgram().getCallGraph();
      if(callGraph == null) return null;
      for(VariableRef varRef : equivalenceClass.getVariables()) {
         Variable variable = getProgram().getScope().getVariable(varRef);
         if(variable == null) continue;
         dk.camelot64.kickc.model.symbols.Scope scope = variable.getScope();
         if(scope instanceof dk.camelot64.kickc.model.symbols.Procedure procedure) {
            if(procedure.getInterruptType() != null || procedure.isDeclaredIntrinsic()) {
               continue;
            }
            if(dk.camelot64.kickc.model.symbols.Procedure.CallingConvention.STACK_CALL.equals(procedure.getCallingConvention())) {
               continue;
            }
            String procName = procedure.getLocalName();
            if("main".equals(procName) || "__start".equals(procName)) {
               continue;
            }
            CallGraph.CallBlock callBlock = callGraph.getCallBlock(procedure.getRef());
            if(callBlock != null && callBlock.getCalls() != null && !callBlock.getCalls().isEmpty()) {
               continue;
            }
            boolean hasCalls = false;
            for(var block : getProgram().getGraph().getAllBlocks()) {
               if(procedure.getRef().equals(block.getScope())) {
                  for(dk.camelot64.kickc.model.statements.Statement stmt : block.getStatements()) {
                     if(stmt instanceof dk.camelot64.kickc.model.statements.StatementCalling) {
                        hasCalls = true;
                        break;
                     }
                  }
               }
               if(hasCalls) break;
            }
            if(hasCalls) continue;

            List<Variable> params = procedure.getParameters();
            if(params != null && !params.isEmpty() && params.size() <= 3) {
               for(int i = 0; i < params.size(); i++) {
                  if(params.get(i).getRef().equals(varRef)) {
                     return i;
                  }
               }
            }
         }
      }
      return null;
   }

   private boolean varVolatile(LiveRangeEquivalenceClass equivalenceClass) {
      for(VariableRef variableRef : equivalenceClass.getVariables()) {
         Variable variable = getSymbols().getVariable(variableRef);
         if(variable.isVolatile() || variable.isKindLoadStore()) {
            return true;
         }
      }
      return false;
   }


}
