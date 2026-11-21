package dk.camelot64.kickc.passes.calcs;

import dk.camelot64.kickc.model.ControlFlowBlock;
import dk.camelot64.kickc.model.StaticAssert;
import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.VariableReferenceInfos;
import dk.camelot64.kickc.model.iterator.ProgramValue;
import dk.camelot64.kickc.model.iterator.ProgramValueIterator;
import dk.camelot64.kickc.model.statements.*;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.values.*;

import java.util.*;

/**
 * Identify variables defined/referenced for each block & statement.
 */
public class PassNCalcVariableReferenceInfos extends PassNCalcBase<VariableReferenceInfos> {

   public PassNCalcVariableReferenceInfos(Program program) {
      super(program);
   }

   @Override
   public VariableReferenceInfos calculate() {
      Map<SymbolVariableRef, Collection<VariableReferenceInfos.ReferenceToSymbolVar>> symbolVarReferences = new LinkedHashMap<>();
      Map<LabelRef, Collection<VariableReferenceInfos.ReferenceToSymbolVar>> blockVarReferences = new LinkedHashMap<>();
      Map<Integer, Collection<VariableReferenceInfos.ReferenceToSymbolVar>> statementVarReferences = new LinkedHashMap<>();

      for(var block : getProgram().getGraph().getAllBlocks()) {
         Collection<VariableReferenceInfos.ReferenceToSymbolVar> blockSymbols =
               blockVarReferences.computeIfAbsent(block.getLabel(), k -> new LinkedHashSet<>());
         for(Statement statement : block.getStatements()) {
            Collection<SymbolVariableRef> stmtUsedVars = getUsedVars(statement);
            Collection<VariableRef> stmtDefinedVars = getDefinedVars(statement);
            // Add variable definitions to the statement
            Collection<VariableReferenceInfos.ReferenceToSymbolVar> stmtSymbols =
                  statementVarReferences.computeIfAbsent(statement.getIndex(), k -> new ArrayList<>());
            // Identify statement defining variables
            for(VariableRef variableRef : stmtDefinedVars) {
               Collection<VariableReferenceInfos.ReferenceToSymbolVar> varReferences =
                     symbolVarReferences.computeIfAbsent(variableRef, k -> new ArrayList<>());
               VariableReferenceInfos.ReferenceInStatement referenceInStmt = new VariableReferenceInfos.ReferenceInStatement(statement.getIndex(), VariableReferenceInfos.ReferenceToSymbolVar.ReferenceType.DEFINE, variableRef);
               varReferences.add(referenceInStmt);
               stmtSymbols.add(referenceInStmt);
               blockSymbols.add(referenceInStmt);
            }
            // Gather statements referencing variables/constants
            for(SymbolVariableRef referencedVarRef : stmtUsedVars) {
               Collection<VariableReferenceInfos.ReferenceToSymbolVar> references =
                     symbolVarReferences.computeIfAbsent(referencedVarRef, k -> new ArrayList<>());
               VariableReferenceInfos.ReferenceInStatement referenceInStmt = new VariableReferenceInfos.ReferenceInStatement(statement.getIndex(), VariableReferenceInfos.ReferenceToSymbolVar.ReferenceType.USE, referencedVarRef);
               references.add(referenceInStmt);
               stmtSymbols.add(referenceInStmt);
               blockSymbols.add(referenceInStmt);
            }
         }
      }

      // Gather symbols in the symbol table referencing other variables/constants
      Collection<Variable> allVariables = getProgram().getScope().getAllVars(true);
      for(Variable referencingVar : allVariables) {
         ProgramValueIterator.execute(referencingVar,
               (programValue, currentStmt, stmtIt, currentBlock) -> {
                  Value rValue = programValue.get();
                  if(rValue instanceof SymbolVariableRef) {
                     SymbolVariableRef referencedVar = (SymbolVariableRef) rValue;
                     Collection<VariableReferenceInfos.ReferenceToSymbolVar> references =
                           symbolVarReferences.computeIfAbsent(referencedVar, k -> new ArrayList<>());
                     references.add(new VariableReferenceInfos.ReferenceInSymbol((SymbolVariableRef) referencingVar.getRef(), VariableReferenceInfos.ReferenceToSymbolVar.ReferenceType.USE, referencedVar));
                  }
               });
      }
      // Gather symbols referenced in static assertions
      for(StaticAssert staticAssert : getProgram().getStaticAsserts()) {
         Collection<SymbolVariableRef> referencedInAssert = new LinkedHashSet<>();
         collectReferenced(staticAssert.getCondition(), referencedInAssert);
         for(SymbolVariableRef ref : referencedInAssert) {
            Collection<VariableReferenceInfos.ReferenceToSymbolVar> references =
                  symbolVarReferences.computeIfAbsent(ref, k -> new ArrayList<>());
            references.add(new VariableReferenceInfos.ReferenceInSymbol(ref, VariableReferenceInfos.ReferenceToSymbolVar.ReferenceType.USE, ref));
         }
      }
      return new VariableReferenceInfos(symbolVarReferences, blockVarReferences, statementVarReferences);
   }

   /**
    * Get all used vars in a statement.
    * This is all referenced vars - but not the variable that is assigned (unless it is also referenced elsewhere in the statement).
    *
    * @param statement The statement
    * @return All used vars
    */
   public static Collection<SymbolVariableRef> getUsedVars(Statement statement) {
      LinkedHashSet<SymbolVariableRef> usedVars = new LinkedHashSet<>();
      if(statement instanceof StatementLValue) {
         final StatementLValue statementLValue = (StatementLValue) statement;
         LValue lValue = statementLValue.getlValue();
         if(lValue instanceof StructMemberRef) {
            RValue root = ((StructMemberRef) lValue).getStruct();
            while(root instanceof StructMemberRef) {
               root = ((StructMemberRef) root).getStruct();
            }
            if(!(root instanceof VariableRef)) {
               collectReferenced(root, usedVars);
            }
         } else if(!(lValue instanceof VariableRef)) {
            // No assignments - find all vars referenced
            collectReferenced(lValue, usedVars);
         }
         if(statementLValue instanceof StatementAssignment) {
            collectReferenced(((StatementAssignment) statementLValue).getrValue1(), usedVars);
            collectReferenced(((StatementAssignment) statementLValue).getrValue2(), usedVars);
         } else if(statementLValue instanceof StatementCall) {
            if(((StatementCall) statement).getParameters() != null)
               for(RValue parameter : ((StatementCall) statementLValue).getParameters()) {
                  collectReferenced(parameter, usedVars);
               }
         } else if(statementLValue instanceof StatementCallPointer) {
            collectReferenced(((StatementCallPointer) statementLValue).getProcedure(), usedVars);
            if(((StatementCallPointer) statement).getParameters() != null)
               for(RValue parameter : ((StatementCallPointer) statementLValue).getParameters()) {
                  collectReferenced(parameter, usedVars);
               }
         } else if(statementLValue instanceof StatementCallFinalize) {
            // No vars
         } else {
            throw new InternalError("Statement type not handled " + statementLValue);
         }
      } else if(statement instanceof StatementPhiBlock) {
         for(StatementPhiBlock.PhiVariable phiVariable : ((StatementPhiBlock) statement).getPhiVariables()) {
            for(StatementPhiBlock.PhiRValue value : phiVariable.getValues()) {
               collectReferenced(value.getrValue(), usedVars);
            }
         }
      } else {
         // No assignments - find all vars referenced
         ProgramValueIterator.execute(statement,
               (programValue, currentStmt, stmtIt, currentBlock) -> {
                  if(programValue.get() instanceof SymbolVariableRef)
                     usedVars.add((SymbolVariableRef) programValue.get());
               }
               , null, null);
      }
      return usedVars;
   }

   /**
    * Get all variables referenced in an rValue
    *
    * @param rValue The rValue
    * @return All referenced variables
    */
   public static Collection<VariableRef> getReferencedVars(RValue rValue) {
      LinkedHashSet<VariableRef> referencedVars = new LinkedHashSet<>();
      for(SymbolRef symbolRef : getReferenced(rValue)) {
         if(symbolRef instanceof VariableRef) {
            referencedVars.add((VariableRef) symbolRef);
         }
      }
      return referencedVars;
   }

   /**
    * Get all constants referenced in an rValue
    *
    * @param rValue The rValue
    * @return All referenced constants
    */
   public static Collection<ConstantRef> getReferencedConsts(RValue rValue) {
      LinkedHashSet<ConstantRef> referencedConsts = new LinkedHashSet<>();
      for(SymbolRef symbolRef : getReferenced(rValue)) {
         if(symbolRef instanceof ConstantRef) {
            referencedConsts.add((ConstantRef) symbolRef);
         }
      }
      return referencedConsts;
   }

   /**
    * Get all variables /constants referenced in an rValue
    *
    * @param rValue The rValue
    * @return All referenced variables / constants
    */
   private static Collection<SymbolVariableRef> getReferenced(RValue rValue) {
      Collection<SymbolVariableRef> referenced = new LinkedHashSet<>();
      collectReferenced(rValue, referenced);
      return referenced;
   }

   private static void collectReferenced(RValue rValue, Collection<SymbolVariableRef> referenced) {
      if(rValue != null) {
         if(rValue instanceof SymbolVariableRef) {
            referenced.add((SymbolVariableRef) rValue);
            return;
         }
         ProgramValueIterator.execute(new ProgramValue.GenericValue(rValue), (programValue, currentStmt, stmtIt, currentBlock) -> {
            if(programValue.get() instanceof SymbolVariableRef) {
               referenced.add((SymbolVariableRef) programValue.get());
            }
         }, null, null, null);
      }
   }

   /**
    * Get the variables defined by a statement
    *
    * @param stmt The statement
    * @return Variables defined by the statement
    */
   public static Collection<VariableRef> getDefinedVars(Statement stmt) {
      if(stmt instanceof StatementPhiBlock) {
         List<VariableRef> defined = new ArrayList<>();
         StatementPhiBlock phi = (StatementPhiBlock) stmt;
         for(StatementPhiBlock.PhiVariable phiVariable : phi.getPhiVariables()) {
            defined.add(phiVariable.getVariable());
         }
         return defined;
      } else if(stmt instanceof StatementLValue) {
         LValue lValue = ((StatementLValue) stmt).getlValue();
         RValue root = lValue;
         while(root instanceof StructMemberRef) {
            root = ((StructMemberRef) root).getStruct();
         }
         if(root instanceof VariableRef) {
            return Collections.singletonList((VariableRef) root);
         }
      }
      return new ArrayList<>();
   }

}
