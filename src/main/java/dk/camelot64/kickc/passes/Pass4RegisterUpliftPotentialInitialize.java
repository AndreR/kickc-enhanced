package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementCalling;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolTypeStruct;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.model.values.SymbolVariableRef;
import dk.camelot64.kickc.model.values.VariableRef;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
      Set<ScopeRef> nonLeafProcedures = findNonLeafProcedures();
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
               Integer leafParamIdx = getLeafProcedureParameterIndex(equivalenceClass, nonLeafProcedures);
               Integer preferredRegIdx = leafParamIdx != null ? leafParamIdx : getStructReturnMemberIndex(equivalenceClass);
               if(preferredRegIdx != null) {
                  if(preferredRegIdx == 0) {
                     potentials.add(Registers.getRegisterA());
                     potentials.add(Registers.getRegisterX());
                     potentials.add(defaultRegister);
                     potentials.add(Registers.getRegisterY());
                  } else if(preferredRegIdx == 1) {
                     potentials.add(Registers.getRegisterX());
                     potentials.add(Registers.getRegisterA());
                     potentials.add(defaultRegister);
                     potentials.add(Registers.getRegisterY());
                  } else {
                     potentials.add(Registers.getRegisterY());
                     potentials.add(defaultRegister);
                     potentials.add(Registers.getRegisterX());
                     if(preferredRegIdx == 2) {
                        potentials.add(Registers.getRegisterA());
                     }
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
   
   private Set<ScopeRef> findNonLeafProcedures() {
      Set<ScopeRef> nonLeafProcedures = new HashSet<>();
      for(ProcedureCompilation procComp : getProgram().getProcedureCompilations()) {
         ControlFlowGraph graph = procComp.getGraph();
         if(graph == null) continue;
         for(Graph.Block block : graph.getAllBlocks()) {
            ScopeRef blockScope = block.getScope();
            if(blockScope == null || nonLeafProcedures.contains(blockScope)) continue;
            for(Statement stmt : block.getStatements()) {
               if(stmt instanceof StatementCalling) {
                  nonLeafProcedures.add(blockScope);
                  break;
               }
            }
         }
      }
      return nonLeafProcedures;
   }

   private Integer getLeafProcedureParameterIndex(LiveRangeEquivalenceClass equivalenceClass, Set<ScopeRef> nonLeafProcedures) {
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
            if(nonLeafProcedures.contains(procedure.getRef())) {
               continue;
            }

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

   /**
    * Identifies if an equivalence class corresponds to an unwound 1-byte member of a 2-byte or 3-byte
    * struct return value for a non-recursive, non-interrupt, non-address-taken procedure.
    * Returns the member index (0 -> prefer A, 1 -> prefer X, 2 -> prefer Y) or null if not applicable.
    */
   private Integer getStructReturnMemberIndex(LiveRangeEquivalenceClass equivalenceClass) {
      StructVariableMemberUnwinding unwinding = getProgram().getStructVariableMemberUnwinding();
      if(unwinding == null) return null;
      CallGraph callGraph = getProgram().getCallGraph();
      for(VariableRef varRef : equivalenceClass.getVariables()) {
         Variable variable = getProgram().getScope().getVariable(varRef);
         if(variable == null) continue;
         Variable masterVar = variable.isKindPhiVersion() ? variable.getPhiMaster() : variable;
         if(masterVar == null) continue;
         Scope scope = masterVar.getScope();
         if(!(scope instanceof Procedure procedure)) continue;
         if(procedure.getInterruptType() != null || procedure.isDeclaredIntrinsic()) continue;
         if(Procedure.CallingConvention.STACK_CALL.equals(procedure.getCallingConvention())) continue;
         String procName = procedure.getLocalName();
         if("main".equals(procName) || "__start".equals(procName)) continue;
         if(callGraph != null && callGraph.getRecursiveCalls(procedure.getRef()).contains(procedure.getRef())) continue;
         if(Pass2ConstantIdentification.isAddressOfUsed(procedure.getRef(), getProgram())) continue;

         Variable procReturnVar = procedure.getLocalVariable("return");
         if(procReturnVar == null || !procReturnVar.isStructUnwind()
               || !(procReturnVar.getType() instanceof SymbolTypeStruct)) {
            continue;
         }
         StructVariableMemberUnwinding.VariableUnwinding returnUnwinding = unwinding.getVariableUnwinding(procReturnVar.getRef());
         if(returnUnwinding == null) continue;
         List<String> memberNames = returnUnwinding.getMemberNames();
         if(memberNames == null || (memberNames.size() != 2 && memberNames.size() != 3)) continue;

         int matchedIdx = -1;
         boolean allSingleByte = true;
         for(int idx = 0; idx < memberNames.size(); idx++) {
            SymbolVariableRef memberRef = returnUnwinding.getMemberUnwound(memberNames.get(idx));
            Variable memberVar = memberRef != null ? getProgram().getScope().getVariable(memberRef) : null;
            if(memberVar == null || memberVar.getType().getSizeBytes() != 1
                  || memberVar.isVolatile() || memberVar.isKindLoadStore()) {
               allSingleByte = false;
               break;
            }
            if(masterVar.getRef().equals(memberRef)) {
               matchedIdx = idx;
            }
         }
         if(allSingleByte && matchedIdx >= 0) {
            return matchedIdx;
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
