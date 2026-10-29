package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.values.ProcedureRef;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.model.values.VariableRef;

import java.util.*;

/**
 * Use the call graph to coalesce memory registers.
 * For each live range equivalence class:
 * - Look up through the call graph and avoid all variables declared in the scopes there
 * - Go through already handled live range equivalence classes and if any exist with no scope overlap with the call graph - try to coalesce
 * - Add to the list of already handled live range equivalence classes
 */
public class Pass4MemoryCoalesceCallGraph extends Pass2Base {

   public Pass4MemoryCoalesceCallGraph(Program program) {
      super(program);
   }

   public void coalesce() {
      LinkedHashSet<String> unknownFragments = new LinkedHashSet<>();
      LiveRangeEquivalenceClassSet liveRangeEquivalenceClassSet = getProgram().getLiveRangeEquivalenceClassSet();
      Collection<ScopeRef> threads = Pass4MemoryCoalesce.getThreadHeads(getProgram());
      boolean modified;
      do {
         modified = coalesce(liveRangeEquivalenceClassSet, threads, unknownFragments);
      } while(modified);

      if(unknownFragments.size() > 0) {
         getLog().append("MISSING FRAGMENTS");
         for(String unknownFragment : unknownFragments) {
            getLog().append("  " + unknownFragment);
         }
      }

   }

   /**
    * Find any two equivalence classes that can be coalesced into one - and perform the coalescence.
    *
    * @param liveRangeEquivalenceClassSet The set of live range equivalence classes
    * @param unknownFragments Receives information about any unknown fragments encountered during ASM generation
    * @return true if any classes were coalesced. False otherwise.
    */
   private boolean coalesce(LiveRangeEquivalenceClassSet liveRangeEquivalenceClassSet, Collection<ScopeRef> threadHeads, Set<String> unknownFragments) {
      boolean modified = false;
      CallGraph callGraph = getProgram().getCallGraph();
      // All live range equivalence classes already handled - partitioned by (type, bytes)
      Map<String, List<LiveRangeEquivalenceClass>> handledECsByTypeAndSize = new LinkedHashMap<>();
      List<LiveRangeEquivalenceClass> allECs = new ArrayList<>(liveRangeEquivalenceClassSet.getEquivalenceClasses());
      Map<LiveRangeEquivalenceClass, Collection<ScopeRef>> ecScopesCache = new HashMap<>();
      for(LiveRangeEquivalenceClass thisEC : allECs) {
         // Skip main-memory registers or non-memory registers
         Registers.Register thisReg = thisEC.getRegister();
         if(Registers.RegisterType.MAIN_MEM.equals(thisReg.getType()) || !thisReg.isMem())
            continue;
         if(thisReg instanceof Registers.RegisterZpMem && getProgram().getReservedZps().contains(((Registers.RegisterZpMem) thisReg).getZp()))
            continue;

         String typeKey = thisReg.getType().name() + ":" + thisReg.getBytes();
         List<LiveRangeEquivalenceClass> handledList = handledECsByTypeAndSize.get(typeKey);

         // Find all calling scopes - since we want to avoid coalescing with variables in these
         Set<ScopeRef> allCallingScopes = null;
         boolean coalesced = false;
         if(handledList != null && !handledList.isEmpty()) {
            for(LiveRangeEquivalenceClass otherEC : handledList) {
               // Fast compatibility check: must be compatible memory registers (same type, same size, not in reserved ZPs)
               if(!Pass4MemoryCoalesce.canCoalesceCompatible(thisEC, otherEC, getProgram()))
                  continue;
               if(allCallingScopes == null) {
                  allCallingScopes = new LinkedHashSet<>();
                  for(ScopeRef ecScope : getEquivalenceClassScopesCached(thisEC, ecScopesCache)) {
                     allCallingScopes.addAll(callGraph.getRecursiveCallerProcs(ecScope));
                  }
               }
               // Skip if there is a scope overlap
               if(isProcedureOverlap(otherEC, allCallingScopes, ecScopesCache))
                  continue;
               // No scope overlap - attempt to coalesce
               Pass4MemoryCoalesce.LiveRangeEquivalenceClassCoalesceCandidate candidate = new Pass4MemoryCoalesce.LiveRangeEquivalenceClassCoalesceCandidate(thisEC, otherEC, null);
               if(Pass4MemoryCoalesce.attemptCoalesce(candidate, threadHeads, unknownFragments, getProgram())) {
                  // Successfully coalesced with already handled EC - invalidate its cached scopes and move on!
                  ecScopesCache.remove(otherEC);
                  coalesced = true;
                  modified = true;
                  break;
               }
            }
         }
         if(!coalesced) {
            // Add to already handled
            handledECsByTypeAndSize.computeIfAbsent(typeKey, k -> new ArrayList<>()).add(thisEC);
         }
      }
      return modified;
   }

   private static Collection<ScopeRef> getEquivalenceClassScopesCached(LiveRangeEquivalenceClass equivalenceClass, Map<LiveRangeEquivalenceClass, Collection<ScopeRef>> cache) {
      Collection<ScopeRef> scopes = cache.get(equivalenceClass);
      if(scopes == null) {
         scopes = getEquivalenceClassScopes(equivalenceClass);
         cache.put(equivalenceClass, scopes);
      }
      return scopes;
   }

   /**
    * Determine if any variable in a live range equivalence class has a scope from the passed set of procedures.
    * @param equivalenceClass The live range equivalence class
    * @param procedureRefs The set of procedures
    * @return true if there is a scope overlap
    */
   private boolean isProcedureOverlap(LiveRangeEquivalenceClass equivalenceClass, Collection<ScopeRef> procedureRefs, Map<LiveRangeEquivalenceClass, Collection<ScopeRef>> cache) {
      Collection<ScopeRef> scopes = getEquivalenceClassScopesCached(equivalenceClass, cache);
      for(ScopeRef otherProcedureRef : scopes) {
         if(procedureRefs.contains(otherProcedureRef))
            return true;
      }
      return false;
   }

   /**
    * Get all scopes of the variables in a live range equivalence class
    * @param equivalenceClass The live range equivalence class
    * @return All scopes
    */
   private static Collection<ScopeRef> getEquivalenceClassScopes(LiveRangeEquivalenceClass equivalenceClass) {
      List<ScopeRef> ecScopes = new ArrayList<>();
      List<VariableRef> variables = equivalenceClass.getVariables();
      for(VariableRef varRef : variables) {
         ScopeRef scopeRef = getScopeRef(varRef);
         if(!ecScopes.contains(scopeRef))
            ecScopes.add(scopeRef);
      }
      return ecScopes;
   }

   private static ScopeRef getScopeRef(VariableRef varRef) {
      ScopeRef scopeRef = new ScopeRef(varRef.getScopeNames());
      if(!ScopeRef.ROOT.equals(scopeRef)) {
         scopeRef = new ProcedureRef(varRef.getScopeNames());
      }
      return scopeRef;
   }


}
