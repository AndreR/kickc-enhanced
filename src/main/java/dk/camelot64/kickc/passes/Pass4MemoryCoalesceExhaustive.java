package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.LiveRangeEquivalenceClass;
import dk.camelot64.kickc.model.LiveRangeEquivalenceClassSet;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.Registers;
import dk.camelot64.kickc.model.values.ScopeRef;

import java.util.*;

/**
 * Exhaustive attempt to coalesces memory registers where live ranges do not overlap.
 * An optional final step done after all other register optimizations and before ASM generation.
 * Performs an exhaustive search - so it can take a lot of time!
 */
public class Pass4MemoryCoalesceExhaustive extends Pass2Base {

   public Pass4MemoryCoalesceExhaustive(Program program) {
      super(program);
   }

   public void coalesce() {
      LinkedHashSet<String> unknownFragments = new LinkedHashSet<>();
      LiveRangeEquivalenceClassSet liveRangeEquivalenceClassSet = getProgram().getLiveRangeEquivalenceClassSet();
      Collection<ScopeRef> threads = Pass4MemoryCoalesce.getThreadHeads(getProgram());
      boolean change;
      do {
         change = coalesce(liveRangeEquivalenceClassSet, threads, unknownFragments);
      } while(change);

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
      boolean anyModified = false;
      Map<String, List<LiveRangeEquivalenceClass>> buckets = new LinkedHashMap<>();
      for(LiveRangeEquivalenceClass ec : liveRangeEquivalenceClassSet.getEquivalenceClasses()) {
         Registers.Register reg = ec.getRegister();
         if(reg == null || !reg.isMem()) continue;
         String key = reg.getType().name() + ":" + reg.getBytes();
         buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(ec);
      }
      for(List<LiveRangeEquivalenceClass> bucket : buckets.values()) {
         for(int i = 0; i < bucket.size(); i++) {
            LiveRangeEquivalenceClass ec1 = bucket.get(i);
            if(!liveRangeEquivalenceClassSet.contains(ec1)) continue;
            for(int j = i + 1; j < bucket.size(); j++) {
               LiveRangeEquivalenceClass ec2 = bucket.get(j);
               if(!liveRangeEquivalenceClassSet.contains(ec2)) continue;
               Pass4MemoryCoalesce.LiveRangeEquivalenceClassCoalesceCandidate candidate =
                     new Pass4MemoryCoalesce.LiveRangeEquivalenceClassCoalesceCandidate(ec1, ec2, null);
               if(Pass4MemoryCoalesce.attemptCoalesce(candidate, threadHeads, unknownFragments, getProgram())) {
                  anyModified = true;
               }
            }
         }
      }
      return anyModified;
   }


}
