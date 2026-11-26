package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.iterator.ProgramValue;
import dk.camelot64.kickc.model.iterator.ProgramValueIterator;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementCalling;
import dk.camelot64.kickc.model.symbols.*;
import dk.camelot64.kickc.model.values.*;

import java.util.*;

/**
 * Performs whole-program transitive reachability analysis from confirmed entry points
 * (start procedure, main, interrupts, root statements, static assertions, and pinned/exported globals)
 * and eliminates unreachable procedures as well as unreachable global variables, constants, and data tables.
 */
public class PassNTransitiveDeadDataElimination extends Pass2SsaOptimization {

   public PassNTransitiveDeadDataElimination(Program program) {
      super(program);
   }

   @Override
   public boolean step() {
      ProgramScope programScope = getProgramScope();

      Set<ProcedureRef> liveProcedures = new LinkedHashSet<>();
      ArrayDeque<ProcedureRef> procQueue = new ArrayDeque<>();
      Set<SymbolVariableRef> liveVars = new LinkedHashSet<>();
      ArrayDeque<SymbolVariableRef> varQueue = new ArrayDeque<>();

      // 1. Seed confirmed entry procedures
      if(getProgram().getStartProcedure() != null) {
         enqueueProc(getProgram().getStartProcedure(), programScope, liveProcedures, procQueue);
      }
      Procedure mainProc = programScope.getLocalProcedure(SymbolRef.MAIN_PROC_NAME);
      if(mainProc != null) {
         enqueueProc(mainProc.getRef(), programScope, liveProcedures, procQueue);
      }
      for(Procedure proc : programScope.getAllProcedures(true)) {
         if(proc.getInterruptType() != null) {
            enqueueProc(proc.getRef(), programScope, liveProcedures, procQueue);
         }
      }

      // 2. Seed any root-scope blocks and static assertions
      for(Graph.Block block : getGraph().getAllBlocks()) {
         if(ScopeRef.ROOT.equals(block.getScope())) {
            for(Statement stmt : block.getStatements()) {
               collectFromStatement(stmt, block, programScope, liveProcedures, procQueue, liveVars, varQueue);
            }
         }
      }
      for(StaticAssert staticAssert : getProgram().getStaticAsserts()) {
         if(staticAssert.getCondition() != null) {
            ProgramValue.GenericValue gv = new ProgramValue.GenericValue(staticAssert.getCondition());
            ProgramValueIterator.execute(gv, (pv, s, it, b) ->
                  collectFromValue(pv.get(), programScope, liveProcedures, procQueue, liveVars, varQueue), null, null, null);
         }
      }

      // 3. Seed pinned / exported / volatile / hardcoded-address global variables and constants
      for(Variable var : programScope.getAllVars(true)) {
         if(isPinnedGlobal(var)) {
            enqueueVar(var.getRef(), liveVars, varQueue);
         }
      }

      // 4. Transitive closure across procedures and data initializers
      while(!procQueue.isEmpty() || !varQueue.isEmpty()) {
         while(!procQueue.isEmpty()) {
            ProcedureRef procRef = procQueue.poll();
            Procedure proc = programScope.getProcedure(procRef);
            if(proc == null) {
               continue;
            }
            for(ProcedureRef ctorRef : proc.getConstructorRefs()) {
               enqueueProc(ctorRef, programScope, liveProcedures, procQueue);
            }
            ProcedureCompilation pc = getProgram().getProcedureCompilation(procRef);
            if(pc != null && pc.getGraph() != null) {
               for(Graph.Block block : pc.getGraph().getAllBlocks()) {
                  for(Statement stmt : block.getStatements()) {
                     collectFromStatement(stmt, block, programScope, liveProcedures, procQueue, liveVars, varQueue);
                  }
               }
            }
         }

         while(!varQueue.isEmpty()) {
            SymbolVariableRef varRef = varQueue.poll();
            Variable var = programScope.getVariable(varRef);
            if(var == null) {
               continue;
            }
            if(var.isKindPhiVersion()) {
               Variable phiMaster = programScope.getVariable(new VariableRef(varRef.getFullNameUnversioned()));
               if(phiMaster != null) {
                  enqueueVar(phiMaster.getRef(), liveVars, varQueue);
               }
            }
            ProgramValueIterator.execute(var, (pv, s, it, b) ->
                  collectFromValue(pv.get(), programScope, liveProcedures, procQueue, liveVars, varQueue));
         }
      }

      boolean modified = false;

      // 5. Remove unreachable procedures (and their blocks + local symbols)
      List<Procedure> allProcedures = new ArrayList<>(programScope.getAllProcedures(true));
      for(Procedure proc : allProcedures) {
         if(proc.isDeclaredIntrinsic()) {
            continue;
         }
         if(!liveProcedures.contains(proc.getRef())) {
            getLog().append("Eliminating unreachable procedure " + proc.getRef());
            getProgram().removeProcedure(proc.getRef());
            modified = true;
         }
      }

      // 6. Remove unreachable non-procedure variables, constants, and data tables
      List<Variable> allVars = new ArrayList<>(programScope.getAllVars(true));
      for(Variable var : allVars) {
         Scope scope = var.getScope();
         if(scope == null || scope instanceof EnumDefinition || scope instanceof StructDefinition) {
            continue;
         }
         if(scope.getContainingProcedure() != null) {
            continue;
         }
         if(isPinnedGlobal(var)) {
            continue;
         }
         if(!liveVars.contains(var.getRef())) {
            getLog().append("Eliminating unreachable global symbol " + var.getRef());
            scope.remove(var);
            modified = true;
         }
      }

      if(modified) {
         getProgram().clearVariableReferenceInfos();
         getProgram().clearControlFlowBlockSuccessorClosure();
         getProgram().clearStatementInfos();
         getProgram().clearStatementIndices();
         getProgram().clearCallGraph();
         getProgram().clearAddressOfProceduresCache();
         getProgram().clearDominators();
         getProgram().clearLoopSet();
      }

      return modified;
   }

   private static boolean isPinnedGlobal(Variable var) {
      if(var.getScope() instanceof EnumDefinition || var.getScope() instanceof StructDefinition) {
         return false;
      }
      if(var.getScope() != null && var.getScope().getContainingProcedure() != null) {
         return false;
      }
      if(var.isExport() || var.isVolatile()) {
         return true;
      }
      if(var.getMemoryAddress() != null) {
         return true;
      }
      return var.getRegister() != null && var.getRegister().isAddressHardcoded();
   }

   private static void enqueueProc(
         ProcedureRef procRef,
         ProgramScope programScope,
         Set<ProcedureRef> liveProcedures,
         ArrayDeque<ProcedureRef> procQueue) {
      if(procRef == null) {
         return;
      }
      if(programScope.getProcedure(procRef) == null) {
         return;
      }
      if(liveProcedures.add(procRef)) {
         procQueue.add(procRef);
      }
   }

   private static void enqueueVar(
         SymbolVariableRef varRef,
         Set<SymbolVariableRef> liveVars,
         ArrayDeque<SymbolVariableRef> varQueue) {
      if(varRef == null) {
         return;
      }
      if(liveVars.add(varRef)) {
         varQueue.add(varRef);
      }
   }

   private static void collectFromStatement(
         Statement stmt,
         Graph.Block block,
         ProgramScope programScope,
         Set<ProcedureRef> liveProcedures,
         ArrayDeque<ProcedureRef> procQueue,
         Set<SymbolVariableRef> liveVars,
         ArrayDeque<SymbolVariableRef> varQueue) {
      if(stmt instanceof StatementCalling calling) {
         enqueueProc(calling.getProcedure(), programScope, liveProcedures, procQueue);
      }
      ProgramValueIterator.execute(stmt, (pv, s, it, b) ->
            collectFromValue(pv.get(), programScope, liveProcedures, procQueue, liveVars, varQueue), null, block);
   }

   private static void collectFromValue(
         Value value,
         ProgramScope programScope,
         Set<ProcedureRef> liveProcedures,
         ArrayDeque<ProcedureRef> procQueue,
         Set<SymbolVariableRef> liveVars,
         ArrayDeque<SymbolVariableRef> varQueue) {
      if(value instanceof ProcedureRef pr) {
         enqueueProc(pr, programScope, liveProcedures, procQueue);
      } else if(value instanceof ConstantSymbolPointer csp) {
         SymbolRef toSym = csp.getToSymbol();
         if(toSym instanceof ProcedureRef pr) {
            enqueueProc(pr, programScope, liveProcedures, procQueue);
         } else if(toSym instanceof SymbolVariableRef svr) {
            enqueueVar(svr, liveVars, varQueue);
         } else if(toSym instanceof LabelRef lr) {
            Symbol sym = programScope.getSymbol(lr);
            if(sym instanceof Procedure p) {
               enqueueProc(p.getRef(), programScope, liveProcedures, procQueue);
            }
         }
      } else if(value instanceof SymbolVariableRef svr) {
         enqueueVar(svr, liveVars, varQueue);
      } else if(value instanceof LabelRef lr) {
         Symbol sym = programScope.getSymbol(lr);
         if(sym instanceof Procedure p) {
            enqueueProc(p.getRef(), programScope, liveProcedures, procQueue);
         }
      }
   }
}
