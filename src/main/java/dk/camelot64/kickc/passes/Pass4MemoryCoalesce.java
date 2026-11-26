package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.values.ProcedureRef;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.model.values.SymbolRef;
import dk.camelot64.kickc.model.values.VariableRef;
import dk.camelot64.kickc.passes.utils.ProcedureUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Coalesces memory registers where their live ranges do not overlap.
 * A final step done after all other register optimizations and before ASM generation.
 */
public abstract class Pass4MemoryCoalesce extends Pass2Base {

   public Pass4MemoryCoalesce(Program program) {
      super(program);
   }

   private static final class CachedThreads {
      final Collection<ScopeRef> threadHeads;
      final Set<ScopeRef> threads;

      CachedThreads(Collection<ScopeRef> threadHeads, Set<ScopeRef> threads) {
         this.threadHeads = threadHeads;
         this.threads = threads;
      }
   }

   private static final Map<LiveRangeEquivalenceClass, CachedThreads> STATIC_THREAD_CACHE =
         Collections.synchronizedMap(new WeakHashMap<>());

   /**
    * Get all functions that represents a separate thread in the program.
    * Each interrupt function and the main() function are threads.
    *
    * @return The threads.
    */
   public static Collection<ScopeRef> getThreadHeads(Program program) {
      Set<ScopeRef> threadHeads = new LinkedHashSet<>();
      Set<ProcedureRef> addressOfProcedures = Pass2ConstantIdentification.getAllAddressOfProcedures(program);
      Collection<Procedure> procedures = program.getScope().getAllProcedures(true);
      for(Procedure procedure : procedures)
         if(ProcedureUtils.isEntrypoint(procedure.getRef(), program, addressOfProcedures))
            threadHeads.add(procedure.getRef());
      return threadHeads;
   }


   /**
    * Determines if two live range equivalence classes can be coalesced.
    *
    * @param ec1 One equivalence class
    * @param ec2 Another equivalence class
    * @param unknownFragments Receives information about any unknown fragments encountered during ASM generation
    * @param program The program
    * @return True if the two equivalence classes can be coalesced into one without problems.
    */
   static boolean canCoalesce(LiveRangeEquivalenceClass ec1, LiveRangeEquivalenceClass ec2, Collection<ScopeRef> threadHeads, Set<String> unknownFragments, Program program) {
      return canCoalesce(ec1, ec2, threadHeads, unknownFragments, program, false, null);
   }

   static boolean canCoalesce(LiveRangeEquivalenceClass ec1, LiveRangeEquivalenceClass ec2, Collection<ScopeRef> threadHeads, Set<String> unknownFragments, Program program, boolean skipClobberCheck, Map<LiveRangeEquivalenceClass, Set<ScopeRef>> threadCache) {
      return
            canCoalesceNotEqual(ec1, ec2) &&
                  canCoalesceCompatible(ec1, ec2, program) &&
                  canCoalesceSegments(ec1, ec2, program) &&
                  canCoalesceVolatile(ec1, ec2, program) &&
                  canCoalesceInterference(ec1, ec2, program) &&
                  canCoalesceThreads(ec1, ec2, threadHeads, program, threadCache) &&
                  (skipClobberCheck || canCoalesceClobber(ec1, ec2, unknownFragments, program));
   }

   private static boolean canCoalesceInterference(LiveRangeEquivalenceClass ec1, LiveRangeEquivalenceClass ec2, Program program) {
      if(program.getLiveRangeInterference() != null) {
         return !program.getLiveRangeInterference().interferes(ec1, ec2);
      }
      return true;
   }

   /**
    * Determines if two live range equivalence classes are candidates for coalescing by checking that they are not the same
    *
    * @param ec1 One equivalence class
    * @param ec2 Another equivalence class
    * @return True if the two equivalence classes can be coalesced into one without problems.
    */
   private static boolean canCoalesceNotEqual(LiveRangeEquivalenceClass ec1, LiveRangeEquivalenceClass ec2) {
      return !ec1.equals(ec2);
   }

   /**
    * Determines if two live range equivalence classes are candidates for coalescing by ensuring they are not from different data segments.
    * @param ec1 One equivalence class
    * @param ec2 Another equivalence class
    * @param program The program
    * @return True if the two equivalence classes can be coalesced into one without problems.
    */
   private static boolean canCoalesceSegments(LiveRangeEquivalenceClass ec1, LiveRangeEquivalenceClass ec2, Program program) {
      final VariableRef variableRef1 = ec1.getVariables().get(0);
      final String dataSegment1 = program.getScope().getVar(variableRef1).getDataSegment();
      final VariableRef variableRef2 = ec2.getVariables().get(0);
      final String dataSegment2 = program.getScope().getVar(variableRef2).getDataSegment();
      if(Objects.equals(dataSegment1, dataSegment2)) {
         return true;
      }
      // If neither equivalence class contains any variable designated for main memory,
      // both are candidates for Zero Page registers and can safely coalesce across segments.
      for(VariableRef varRef : ec1.getVariables()) {
         Variable v = program.getScope().getVar(varRef);
         if(v != null && v.isMemoryAreaMain()) return false;
      }
      for(VariableRef varRef : ec2.getVariables()) {
         Variable v = program.getScope().getVar(varRef);
         if(v != null && v.isMemoryAreaMain()) return false;
      }
      return true;
   }

   private static boolean canCoalesceThreads(LiveRangeEquivalenceClass ec1, LiveRangeEquivalenceClass ec2, Collection<ScopeRef> threadHeads, Program program, Map<LiveRangeEquivalenceClass, Set<ScopeRef>> threadCache) {
      if(threadHeads.size() <= 1) {
         return true;
      }
      CallGraph callGraph = program.getCallGraph();
      Collection<ScopeRef> threads1 = getEquivalenceClassThreads(ec1, program, threadHeads, callGraph, threadCache);
      Collection<ScopeRef> threads2 = getEquivalenceClassThreads(ec2, program, threadHeads, callGraph, threadCache);
      if(threads1.isEmpty() || threads2.isEmpty()) {
         return true;
      }
      return threads1.equals(threads2);
   }

   /**
    * Find the threads for the variables in an equivalence class, using the provided cache if available.
    */
   private static Set<ScopeRef> getEquivalenceClassThreads(LiveRangeEquivalenceClass equivalenceClass, Program program, Collection<ScopeRef> threadHeads, CallGraph callGraph, Map<LiveRangeEquivalenceClass, Set<ScopeRef>> threadCache) {
      if(threadCache != null) {
         Set<ScopeRef> cached = threadCache.get(equivalenceClass);
         if(cached != null) {
            return cached;
         }
         Set<ScopeRef> threads = computeEquivalenceClassThreads(equivalenceClass, program, threadHeads, callGraph);
         threadCache.put(equivalenceClass, threads);
         return threads;
      }
      CachedThreads cached = STATIC_THREAD_CACHE.get(equivalenceClass);
      if(cached != null && cached.threadHeads == threadHeads) {
         return cached.threads;
      }
      Set<ScopeRef> threads = computeEquivalenceClassThreads(equivalenceClass, program, threadHeads, callGraph);
      STATIC_THREAD_CACHE.put(equivalenceClass, new CachedThreads(threadHeads, threads));
      return threads;
   }

   /**
    * Find the threads for the variables in an equivalence class.
    *
    * @param equivalenceClass The equivalence class
    * @param program The program
    * @param threadHeads The threads (heads)
    * @param callGraph The call graph
    * @return All threads containing variables in the equivalence class.
    */
   private static Set<ScopeRef> computeEquivalenceClassThreads(LiveRangeEquivalenceClass equivalenceClass, Program program, Collection<ScopeRef> threadHeads, CallGraph callGraph) {
      Set<ScopeRef> threads = new LinkedHashSet<>();
      VariableReferenceInfos refInfos = program.getVariableReferenceInfos();
      StatementInfos stmtInfos = program.getStatementInfos();
      for(VariableRef varRef : equivalenceClass.getVariables()) {
         Variable variable = program.getScope().getVariable(varRef);
         ScopeRef scopeRef = variable.getScope().getRef();
         if(scopeRef.equals(ScopeRef.ROOT)) {
            // SSA-05: Determine actual accessing threads via VariableReferenceInfos instead of blindly assigning main
            Collection<Integer> stmtIndices = refInfos.getVarRefStatements(varRef);
            boolean foundAnyThread = false;
            if(stmtIndices != null) {
               for(Integer stmtIdx : stmtIndices) {
                  Graph.Block block = stmtInfos.getBlock(stmtIdx);
                  if(block == null) continue;
                  ScopeRef blockScopeRef = block.getScope();
                  dk.camelot64.kickc.model.symbols.Scope blockScope = program.getScope().getScope(blockScopeRef);
                  while(blockScope != null && !(blockScope instanceof Procedure) && !blockScope.getRef().equals(ScopeRef.ROOT)) {
                     blockScope = blockScope.getScope();
                  }
                  if(blockScope instanceof Procedure proc) {
                     ScopeRef procRef = proc.getRef();
                     if(threadHeads.contains(procRef)) {
                        threads.add(procRef);
                        foundAnyThread = true;
                     }
                     Collection<ScopeRef> recursiveCallers = callGraph.getRecursiveCallerProcs(procRef);
                     for(ScopeRef threadHead : threadHeads) {
                        if(recursiveCallers.contains(threadHead)) {
                           threads.add(threadHead);
                           foundAnyThread = true;
                        }
                     }
                  }
               }
            }
            if(!foundAnyThread) {
               ProcedureRef startProc = program.getStartProcedure();
               if(startProc != null) {
                  threads.add(startProc);
               } else {
                  Procedure mainProc = program.getScope().getLocalProcedure(SymbolRef.MAIN_PROC_NAME);
                  if(mainProc != null) {
                     threads.add(mainProc.getRef());
                  }
               }
            }
         } else {
            Collection<ScopeRef> recursiveCallers = callGraph.getRecursiveCallerProcs(scopeRef);
            for(ScopeRef threadHead : threadHeads) {
               if(recursiveCallers.contains(threadHead) || threadHead.equals(scopeRef)) {
                  threads.add(threadHead);
               }
            }
         }
      }
      return threads;
   }

   /**
    * Collect all statement indices referencing any variable in an equivalence class.
    */
   static Set<Integer> getEquivalenceClassStatements(LiveRangeEquivalenceClass ec, Program program) {
      VariableReferenceInfos refInfos = program.getVariableReferenceInfos();
      Set<Integer> stmts = new HashSet<>();
      for(VariableRef varRef : ec.getVariables()) {
         Collection<Integer> varStmts = refInfos.getVarRefStatements(varRef);
         if(varStmts != null) {
            stmts.addAll(varStmts);
         }
      }
      return stmts;
   }

   /**
    * Determines if two equivalence classes share any referencing statement.
    */
   static boolean sharesAnyStatement(LiveRangeEquivalenceClass ec1, LiveRangeEquivalenceClass ec2, Program program) {
      Set<Integer> ec1Stmts = getEquivalenceClassStatements(ec1, program);
      return sharesAnyStatement(ec1Stmts, ec2, program);
   }

   /**
    * Determines if a precomputed statement set for an equivalence class shares any statement with another equivalence class.
    */
   static boolean sharesAnyStatement(Set<Integer> ec1Stmts, LiveRangeEquivalenceClass ec2, Program program) {
      if(ec1Stmts == null || ec1Stmts.isEmpty()) {
         return false;
      }
      VariableReferenceInfos refInfos = program.getVariableReferenceInfos();
      for(VariableRef varRef : ec2.getVariables()) {
         Collection<Integer> varStmts = refInfos.getVarRefStatements(varRef);
         if(varStmts != null) {
            for(Integer stmtIdx : varStmts) {
               if(ec1Stmts.contains(stmtIdx)) {
                  return true;
               }
            }
         }
      }
      return false;
   }

   /**
    * Determines if two live range equivalence classes have compatible registers.
    * The registers are compatible if they are in memory and have the same type and size.
    *
    * @param ec1 One equivalence class
    * @param ec2 Another equivalence class
    * @param program The program
    * @return True if the two equivalence classes can be coalesced into one without problems.
    */
   static boolean canCoalesceCompatible(LiveRangeEquivalenceClass ec1, LiveRangeEquivalenceClass ec2, Program program) {
      Registers.Register register1 = ec1.getRegister();
      Registers.Register register2 = ec2.getRegister();
      // Check the both registers are in memory
      if(!register1.isMem() || !register2.isMem())
         return false;
      // Never coalesce registers with hardcoded address
      if(register1.isAddressHardcoded() || register2.isAddressHardcoded())
         return false;
      // Check the both registers have the same type
      if(!register1.getType().equals(register2.getType()))
         return false;
      // Check the both registers have the same size
      if(register1.getBytes() != register2.getBytes())
         return false;
      // check if either are in the reserved zp registers
      if(register1 instanceof Registers.RegisterZpMem) {
         int zp = ((Registers.RegisterZpMem) register1).getZp();
         if(program.getReservedZps().contains(zp))
            return false;
      }
      if(register2 instanceof Registers.RegisterZpMem) {
         int zp = ((Registers.RegisterZpMem) register2).getZp();
         if(program.getReservedZps().contains(zp))
            return false;
      }
      return true;
}

   /**
    * Determines if two live range equivalence classes can be coalesced without clobber.
    * This is possible if they are both allocated to zero page, have the same size and the resulting ASM has no live range overlaps or clobber issues.
    *
    * @param ec1 One equivalence class
    * @param ec2 Another equivalence class
    * @param unknownFragments Receives information about any unknown fragments encountered during ASM generation
    * @param program The program
    * @return True if the two equivalence classes can be coalesced into one without problems.
    */
   private static boolean canCoalesceClobber(LiveRangeEquivalenceClass ec1, LiveRangeEquivalenceClass ec2, Set<String> unknownFragments, Program program) {
      Registers.Register register1 = ec1.getRegister();
      // Try out the coalesce to test if it works
      RegisterCombination combination = new RegisterCombination();
      combination.setRegister(ec2, register1);
      return Pass4RegisterUpliftCombinations.generateCombinationAsm(combination, program, unknownFragments, ScopeRef.ROOT);
   }

   /**
    * Determines if any volatile variables prevents coalescing two equivalence classes
    *
    * @param ec1 One equivalence class
    * @param ec2 Another equivalence class
    * @param program The program
    * @return True if the two equivalence classes can be coalesced into one without problems with volatility.
    */
   private static boolean canCoalesceVolatile(LiveRangeEquivalenceClass ec1, LiveRangeEquivalenceClass ec2, Program program) {
      // If any variable inside is volatile only allow coalesceing with itself
      if(ec1.hasVolatile(program) || ec2.hasVolatile(program)) {
         Variable baseVar1 = ec1.getSingleVariableBase(program);
         Variable baseVar2 = ec2.getSingleVariableBase(program);
         if(baseVar1 == null || baseVar2 == null) {
            // One of the equivalence classes have different base variables inside
            return false;
         }
         if(!baseVar1.equals(baseVar2)) {
            // The two equivalence classes have different base variables
            return false;
         }
      }
      return true;
   }


   /**
    * Try to coalesce two live range equivalence classes
    *
    * @param candidate The candidate for coalescing
    * @param threadHeads The thread heads (get using {@link #getThreadHeads(Program)})
    * @param unknownFragments Receives information about ASM fragments that can't be created during the coalescence).
    * @param program The program.
    * @return true if the coalescence succeeds and the program was updated. false otherwise.
    */
   public static boolean attemptCoalesce(LiveRangeEquivalenceClassCoalesceCandidate candidate, Collection<ScopeRef> threadHeads, Set<String> unknownFragments, Program program) {
      return attemptCoalesce(candidate, threadHeads, unknownFragments, program, false, null);
   }

   public static boolean attemptCoalesce(LiveRangeEquivalenceClassCoalesceCandidate candidate, Collection<ScopeRef> threadHeads, Set<String> unknownFragments, Program program, boolean skipClobberCheck, Map<LiveRangeEquivalenceClass, Set<ScopeRef>> threadCache) {
      LiveRangeEquivalenceClassSet liveRangeEquivalenceClassSet = program.getLiveRangeEquivalenceClassSet();
      LiveRangeEquivalenceClass ec1 = candidate.getEc1();
      LiveRangeEquivalenceClass ec2 = candidate.getEc2();
      if(liveRangeEquivalenceClassSet.contains(ec1) && liveRangeEquivalenceClassSet.contains(ec2)) {
         // Both equivalence classes still exist
         if(Pass4MemoryCoalesce.canCoalesce(ec1, ec2, threadHeads, unknownFragments, program, skipClobberCheck, threadCache)) {
            String scoreString = (candidate.getScore() == null) ? "" : (" - score: " + candidate.getScore());
            program.getLog().append("Coalescing zero page register [ " + ec1 + " ] with [ " + ec2 + " ]" + scoreString);
            liveRangeEquivalenceClassSet.consolidate(ec1, ec2);
            if(threadCache != null) {
               Set<ScopeRef> t1 = threadCache.get(ec1);
               Set<ScopeRef> t2 = threadCache.get(ec2);
               if(t1 != null && t2 != null) {
                  Set<ScopeRef> merged = new LinkedHashSet<>(t1);
                  merged.addAll(t2);
                  threadCache.put(ec1, merged);
               } else {
                  threadCache.remove(ec1);
               }
               threadCache.remove(ec2);
            }
            STATIC_THREAD_CACHE.remove(ec1);
            STATIC_THREAD_CACHE.remove(ec2);
            // Update the register allocation for the merged equivalence class's variables
            Registers.Register register = ec1.getRegister();
            SymbolInfos symbolInfos = program.getSymbolInfos();
            for(VariableRef varRef : ec1.getVariables()) {
               Variable var = symbolInfos.getVariable(varRef);
               if(var != null) {
                  var.setAllocation(register);
               }
            }
            if(program.getLiveRangeInterference() != null) program.getLiveRangeInterference().consolidate(ec1, ec2);
            return true;
         }
      }
      return false;
   }


/**
 * A pair of live range equivalence classes that are candidates for coalescing.
 * The pair is unordered - meaning it is equal to the pair with the same classes in opposite order.
 */
static class LiveRangeEquivalenceClassCoalesceCandidate {
   private LiveRangeEquivalenceClass ec1;
   private LiveRangeEquivalenceClass ec2;
   private Integer score;

   public LiveRangeEquivalenceClassCoalesceCandidate(LiveRangeEquivalenceClass ec1, LiveRangeEquivalenceClass ec2, Integer score) {
      this.ec1 = ec1;
      this.ec2 = ec2;
      this.score = score;
   }

   @Override
   public boolean equals(Object o) {
      if(this == o) return true;
      if(o == null || getClass() != o.getClass()) return false;
      LiveRangeEquivalenceClassCoalesceCandidate that = (LiveRangeEquivalenceClassCoalesceCandidate) o;
      if(ec1.equals(that.ec1) && ec2.equals(that.ec2)) return true;
      if(ec1.equals(that.ec2) && ec2.equals(that.ec1)) return true;
      return false;
   }

   @Override
   public int hashCode() {
      return ec1.hashCode() + ec2.hashCode();
   }

   public Integer getScore() {
      return score;
   }

   public LiveRangeEquivalenceClass getEc1() {
      return ec1;
   }

   public LiveRangeEquivalenceClass getEc2() {
      return ec2;
   }
}
}
