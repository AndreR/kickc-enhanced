package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.ControlFlowBlock;
import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.VariableReferenceInfos;
import dk.camelot64.kickc.model.statements.*;
import dk.camelot64.kickc.model.symbols.EnumDefinition;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.StructDefinition;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.values.LValue;
import dk.camelot64.kickc.model.values.StructUnwoundPlaceholder;
import dk.camelot64.kickc.model.values.VariableRef;

import java.util.Collection;
import java.util.ListIterator;

/**
 * Eliminate unused variables or constants
 */
public class PassNEliminateUnusedVars extends Pass2SsaOptimization {

   private boolean pass2;

   public PassNEliminateUnusedVars(Program program, boolean pass2) {
      super(program);
      this.pass2 = pass2;
   }

   @Override
   public boolean step() {
      new PassNStatementIndices(getProgram()).execute();
      getProgram().clearVariableReferenceInfos();
      getProgram().clearControlFlowBlockSuccessorClosure();
      VariableReferenceInfos referenceInfos = getProgram().getVariableReferenceInfos();
      boolean modified = false;
      java.util.Set<dk.camelot64.kickc.model.values.SymbolVariableRef> retainedVars = new java.util.HashSet<>();
      for(var block : getGraph().getAllBlocks()) {
         ListIterator<Statement> stmtIt = block.getStatements().listIterator();
         while(stmtIt.hasNext()) {
            Statement statement = stmtIt.next();
            if(statement instanceof StatementAssignment) {
               LValue lValue = ((StatementAssignment) statement).getlValue();
               if(lValue instanceof VariableRef vr) {
                  Variable variable = getProgramScope().getVariable(vr);
                  if(eliminate(variable, referenceInfos, statement)) {
                     if(pass2 || getLog().isVerbosePass1CreateSsa()) {
                        getLog().append("Eliminating unused variable " + lValue.toString(getProgram()) + " and assignment " + statement.toString(getProgram(), false));
                     }
                     stmtIt.remove();
                     if(variable != null) {
                        variable.getScope().remove(variable);
                     }
                     modified = true;
                  } else {
                     retainedVars.add(vr);
                  }
               }
            } else if(statement instanceof StatementCall) {
               LValue lValue = ((StatementCall) statement).getlValue();
               if(lValue instanceof VariableRef vr) {
                  Variable variable = getProgramScope().getVariable(vr);
                  if(eliminate(variable, referenceInfos, statement)) {
                     if(pass2 || getLog().isVerbosePass1CreateSsa()) {
                        getLog().append("Eliminating unused variable - keeping the call " + lValue.toString(getProgram()));
                     }
                     if(variable != null) {
                        variable.getScope().remove(variable);
                     }
                     ((StatementCall) statement).setlValue(null);
                     modified = true;
                  } else {
                     retainedVars.add(vr);
                  }
               }
            } else if(statement instanceof StatementCallFinalize) {
               LValue lValue = ((StatementCallFinalize) statement).getlValue();
               if(lValue instanceof VariableRef vr) {
                  Variable variable = getProgramScope().getVariable(vr);
                  if(eliminate(variable, referenceInfos, statement)) {
                     if(pass2 || getLog().isVerbosePass1CreateSsa()) {
                        getLog().append("Eliminating unused variable - keeping the call " + lValue.toString(getProgram()));
                     }
                     if(variable != null) {
                        variable.getScope().remove(variable);
                     }
                     ((StatementCallFinalize) statement).setlValue(null);
                     modified = true;
                  } else {
                     retainedVars.add(vr);
                  }
               }
            } else if(statement instanceof StatementCallPointer) {
               LValue lValue = ((StatementCallPointer) statement).getlValue();
               if(lValue instanceof VariableRef vr) {
                  Variable variable = getProgramScope().getVariable(vr);
                  if(eliminate(variable, referenceInfos, statement)) {
                     if(pass2 || getLog().isVerbosePass1CreateSsa()) {
                        getLog().append("Eliminating unused variable - keeping the call " + lValue.toString(getProgram()));
                     }
                     if(variable != null) {
                        variable.getScope().remove(variable);
                     }
                     ((StatementCallPointer) statement).setlValue(null);
                     modified = true;
                  } else {
                     retainedVars.add(vr);
                  }
               }
            } else if(statement instanceof StatementPhiBlock) {
               StatementPhiBlock statementPhi = (StatementPhiBlock) statement;
               ListIterator<StatementPhiBlock.PhiVariable> phiVarIt = statementPhi.getPhiVariables().listIterator();
               while(phiVarIt.hasNext()) {
                  StatementPhiBlock.PhiVariable phiVariable = phiVarIt.next();
                  VariableRef variableRef = phiVariable.getVariable();
                  Variable variable = getProgramScope().getVariable(variableRef);
                  if(eliminate(variable, referenceInfos, statement)) {
                     if(pass2 || getLog().isVerbosePass1CreateSsa()) {
                        getLog().append("Eliminating unused variable - keeping the phi block " + variableRef.toString(getProgram()));
                     }
                     if(variable != null) {
                        variable.getScope().remove(variable);
                     }
                     phiVarIt.remove();
                     modified = true;
                  } else {
                     retainedVars.add(variableRef);
                  }
               }
            }
         }
      }

      for(Variable variable : getProgramScope().getAllVariables(true)) {
         if(!retainedVars.contains(variable.getRef()) && eliminate(variable, referenceInfos, null) && !variable.isKindPhiMaster()) {
            getLog().append("Eliminating unused variable with no statement " + variable.getRef().toString(getProgram()));
            variable.getScope().remove(variable);
         }
      }

      Collection<Variable> allConstants = getProgramScope().getAllConstants(true);
      for(Variable constant : allConstants) {
         if(eliminate(constant, referenceInfos, null)) {
            if(pass2 || getLog().isVerbosePass1CreateSsa()) {
               getLog().append("Eliminating unused constant " + constant.toString(getProgram()));
            }
            constant.getScope().remove(constant);
            modified = true;
         }
      }

      getProgram().clearVariableReferenceInfos();
      getProgram().clearControlFlowBlockSuccessorClosure();
      getProgram().clearStatementIndices();
      return modified;
   }

   /**
    * Is the variable an unused variable that should be eliminated
    *
    * @param variable The variable
    * @param referenceInfos The reference info
    * @param statement The statement
    * @return true if the variable & statement should be eliminated
    */
   private boolean eliminate(Variable variable, VariableReferenceInfos referenceInfos, Statement statement) {
      if(variable == null)
         // Eliminate if already deleted
         return true;
      if(variable.getScope() instanceof EnumDefinition || variable.getScope() instanceof StructDefinition)
         // Do not eliminate inside enums or structs
         return false;
      if(!pass2 && isReturnValue(variable)) {
         // Do not eliminate return variables in pass 1
         return false;
      }
      if(variable.getScope() instanceof Procedure && ((Procedure) variable.getScope()).getParameters().contains(variable)) {
         // Do not eliminate parameters
         return false;
      }
      if(variable.getMemoryAddress() != null) {
         // Do not eliminate variables with hardcoded memory address
         return false;
      }
      if(variable.getRegister() != null && variable.getRegister().isAddressHardcoded()) {
         // Do not eliminate variables with hardcoded register / address
         return false;
      }
      if(variable.isVolatile()) {
         // Do not eliminate volatile variables
         return false;
      }
      if(statement instanceof StatementAssignment assignment && hasVolatileRhs(assignment)) {
         // Do not eliminate assignments whose RHS performs a volatile read or pointer dereference
         return false;
      }
      final boolean unused = referenceInfos.isUnused(variable.getRef());
      if(!unused)
         // Do not eliminate is not unused
         return false;
      if(!variable.isExport())
         // Eliminate if unused and not exported
         return true;
      if(variable.isStruct() && statement instanceof StatementAssignment) {
         StatementAssignment assignment = (StatementAssignment) statement;
         // Eliminate is a simple assignment of a struct-unwound-placeholder
         return assignment.getOperator() == null && assignment.getrValue2() instanceof StructUnwoundPlaceholder;
      }
      return false;
   }

   private boolean hasVolatileRhs(StatementAssignment assignment) {
      final boolean[] found = {false};
      if(dk.camelot64.kickc.model.operators.Operators.DEREF.equals(assignment.getOperator())) {
         if(isVolatilePointerValue(assignment.getrValue2())) return true;
      } else if(dk.camelot64.kickc.model.operators.Operators.DEREF_IDX.equals(assignment.getOperator())) {
         if(isVolatilePointerValue(assignment.getrValue1())) return true;
      }
      if(assignment.getrValue1() != null) {
         dk.camelot64.kickc.model.iterator.ProgramValueIterator.execute(new dk.camelot64.kickc.model.iterator.ProgramValue.GenericValue(assignment.getrValue1()), (pv, s, it, b) -> {
            dk.camelot64.kickc.model.values.Value v = pv.get();
            if(v instanceof dk.camelot64.kickc.model.values.PointerDereference deref) {
               if(isVolatilePointerValue(deref.getPointer())) found[0] = true;
            } else if(v instanceof dk.camelot64.kickc.model.values.SymbolVariableRef svr) {
               Variable var = getProgramScope().getVariable(svr);
               if(var != null && var.isVolatile()) found[0] = true;
            }
         }, null, null, null);
      }
      if(found[0]) return true;
      if(assignment.getrValue2() != null) {
         dk.camelot64.kickc.model.iterator.ProgramValueIterator.execute(new dk.camelot64.kickc.model.iterator.ProgramValue.GenericValue(assignment.getrValue2()), (pv, s, it, b) -> {
            dk.camelot64.kickc.model.values.Value v = pv.get();
            if(v instanceof dk.camelot64.kickc.model.values.PointerDereference deref) {
               if(isVolatilePointerValue(deref.getPointer())) found[0] = true;
            } else if(v instanceof dk.camelot64.kickc.model.values.SymbolVariableRef svr) {
               Variable var = getProgramScope().getVariable(svr);
               if(var != null && var.isVolatile()) found[0] = true;
            }
         }, null, null, null);
      }
      return found[0];
   }

   /**
    * HOCH-06: Only treat a PointerDereference as having side effects if it points to volatile memory
    * (SymbolTypePointer element isVolatile / Variable.isToVolatile()) or a constant hardware I/O address ($0000-$0001, $D000-$DFFF).
    */
   private boolean isVolatilePointerValue(dk.camelot64.kickc.model.values.RValue ptr) {
      if(ptr == null) return false;
      try {
         dk.camelot64.kickc.model.types.SymbolType ptrType = dk.camelot64.kickc.model.types.SymbolTypeInference.inferType(getProgramScope(), ptr);
         if(ptrType instanceof dk.camelot64.kickc.model.types.SymbolTypePointer stp
               && stp.getElementType() != null && stp.getElementType().isVolatile()) {
            return true;
         }
      } catch(Exception ignored) {}
      dk.camelot64.kickc.model.values.RValue unwrapped = ptr;
      while(unwrapped instanceof dk.camelot64.kickc.model.values.CastValue
            || unwrapped instanceof dk.camelot64.kickc.model.values.ConstantCastValue) {
         if(unwrapped instanceof dk.camelot64.kickc.model.values.CastValue cv) {
            if(cv.getToType() instanceof dk.camelot64.kickc.model.types.SymbolTypePointer stp
                  && stp.getElementType() != null && stp.getElementType().isVolatile()) {
               return true;
            }
            unwrapped = cv.getValue();
         } else {
            dk.camelot64.kickc.model.values.ConstantCastValue ccv = (dk.camelot64.kickc.model.values.ConstantCastValue) unwrapped;
            if(ccv.getToType() instanceof dk.camelot64.kickc.model.types.SymbolTypePointer stp
                  && stp.getElementType() != null && stp.getElementType().isVolatile()) {
               return true;
            }
            unwrapped = ccv.getValue();
         }
      }
      if(unwrapped instanceof dk.camelot64.kickc.model.values.SymbolVariableRef svr) {
         Variable var = getProgramScope().getVariable(svr);
         if(var != null && (var.isVolatile() || var.isToVolatile())) {
            return true;
         }
         if(var != null && var.isKindConstant() && var.getInitValue() != null) {
            if(isHardwareIoConstant(var.getInitValue())) {
               return true;
            }
         }
      }
      if(unwrapped instanceof dk.camelot64.kickc.model.values.ConstantValue cv) {
         if(isHardwareIoConstant(cv)) {
            return true;
         }
      }
      return false;
   }

   private boolean isHardwareIoConstant(dk.camelot64.kickc.model.values.ConstantValue cv) {
      try {
         dk.camelot64.kickc.model.values.ConstantLiteral lit = cv.calculateLiteral(getProgramScope());
         Long addr = null;
         if(lit instanceof dk.camelot64.kickc.model.values.ConstantPointer cp) {
            addr = cp.getLocation();
         } else if(lit instanceof dk.camelot64.kickc.model.values.ConstantInteger ci) {
            addr = ci.getValue();
         }
         if(addr != null && ((addr >= 0x0000L && addr <= 0x0001L) || (addr >= 0xd000L && addr <= 0xdfffL))) {
            return true;
         }
      } catch(Exception ignored) {}
      return false;
   }

   /**
    * Determines if a variable is the return value for a procedure
    *
    * @param variable The variable
    * @return true if this is the return variable for a function
    */
   private boolean isReturnValue(Variable variable) {
      if(variable == null) return false;
      return variable.getScope() instanceof Procedure && variable.getLocalName().equals("return");
   }


}
