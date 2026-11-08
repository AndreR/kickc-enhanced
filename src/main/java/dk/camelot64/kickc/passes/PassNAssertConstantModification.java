package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.statements.StatementLValue;
import dk.camelot64.kickc.model.statements.StatementSource;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.values.ConstantRef;
import dk.camelot64.kickc.model.values.ConstantSymbolPointer;
import dk.camelot64.kickc.model.values.LValue;
import dk.camelot64.kickc.model.values.PointerDereferenceIndexed;
import dk.camelot64.kickc.model.values.PointerDereferenceSimple;
import dk.camelot64.kickc.model.values.RValue;
import dk.camelot64.kickc.model.values.SymbolRef;
import dk.camelot64.kickc.model.values.VariableRef;

import java.util.HashSet;
import java.util.Set;

/**
 * Asserts that constants and ROM segment symbols are not modified.
 */
public class PassNAssertConstantModification extends Pass2SsaOptimization {

   public PassNAssertConstantModification(Program program) {
      super(program);
   }

   @Override
   public boolean step() {
      Set<VariableRef> assigned = new HashSet<>();
      for(var statement : getGraph().getAllStatements()) {
         if(statement instanceof final StatementLValue statementLValue) {
            LValue lValue = statementLValue.getlValue();
            checkLValue(lValue, statement.getSource(), assigned);
         }
      }
      return false;
   }

   private void checkLValue(LValue lValue, StatementSource source, Set<VariableRef> assigned) {
      if(lValue instanceof VariableRef variableRef) {
         Variable variable = getProgramScope().getVariable(variableRef);
         if(variable == null) return;
         if(getProgram().isSegmentRom(variable.getDataSegment())) {
            throw new CompileError("cannot assign to symbol '" + variable.getLocalName() + "' residing in read-only ROM segment '" + variable.getDataSegment() + "'", source);
         }
         if(variable.isKindConstant() || variable.isNoModify()) {
            if(assigned.contains(variableRef)) {
               throw new CompileError("const variable may not be modified " + variable.toString(getProgram()), source);
            } else {
               assigned.add(variableRef);
            }
         }
      } else if(lValue instanceof PointerDereferenceIndexed indexed) {
         checkPointerBase(indexed.getPointer(), source);
      } else if(lValue instanceof PointerDereferenceSimple simple) {
         checkPointerBase(simple.getPointer(), source);
      }
   }

   private void checkPointerBase(RValue pointer, StatementSource source) {
      Variable var = null;
      if(pointer instanceof VariableRef varRef) {
         var = getProgramScope().getVariable(varRef);
      } else if(pointer instanceof ConstantRef constRef) {
         var = getProgramScope().getConstant(constRef);
      } else if(pointer instanceof ConstantSymbolPointer symbolPointer) {
         SymbolRef targetSymbol = symbolPointer.getToSymbol();
         if(targetSymbol instanceof VariableRef varRef) {
            var = getProgramScope().getVariable(varRef);
         } else if(targetSymbol instanceof ConstantRef constRef) {
            var = getProgramScope().getConstant(constRef);
         }
      }
      if(var != null && getProgram().isSegmentRom(var.getDataSegment())) {
         throw new CompileError("cannot assign to symbol '" + var.getLocalName() + "' residing in read-only ROM segment '" + var.getDataSegment() + "'", source);
      }
   }
}
