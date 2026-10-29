package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.model.values.VariableRef;
import dk.camelot64.kickc.model.statements.Statement;

import java.util.*;

/**
 * Coalesces memory registers where their live ranges do not overlap.
 * This step tries to coalesce lvalues with rvalues for all assignments - saving both cycles & bytes if successful.
 */
public class Pass4MemoryCoalesceAssignment extends Pass2Base {

   public Pass4MemoryCoalesceAssignment(Program program) {
      super(program);
   }

   public void coalesce() {
      CoalesceVarScores coalesceVarScores = new CoalesceVarScores(getProgram());
      LinkedHashSet<String> unknownFragments = new LinkedHashSet<>();
      Collection<ScopeRef> threadHeads = Pass4MemoryCoalesce.getThreadHeads(getProgram());

      boolean change;
      do {
         change = false;
         CoalesceLiveRangeEquivalenceClassScores equivalenceClassScores =
               new CoalesceLiveRangeEquivalenceClassScores(getProgram(), coalesceVarScores);
         List<Pass4MemoryCoalesce.LiveRangeEquivalenceClassCoalesceCandidate> coalesceCandidates =
               equivalenceClassScores.getCoalesceCandidates();
         for(Pass4MemoryCoalesce.LiveRangeEquivalenceClassCoalesceCandidate candidate : coalesceCandidates) {
            change |= Pass4MemoryCoalesce.attemptCoalesce(candidate, threadHeads, unknownFragments, getProgram());
         }
      } while(change);

      if(unknownFragments.size() > 0) {
         getLog().append("MISSING FRAGMENTS");
         for(String unknownFragment : unknownFragments) {
            getLog().append("  " + unknownFragment);
         }
      }
   }


   /**
    * Scores for coalescing any pair of variables.
    * Scores are increased when one variable is an lvalue and the other an rvalue in an assignment.
    */
   private static class CoalesceVarScores {

      /** Maps var to scores for coalescing with other variables */
      private Map<VariableRef, Map<VariableRef, Integer>> scores;

      public CoalesceVarScores(Program program) {
         this.scores = new LinkedHashMap<>();
         VariableReferenceInfos variableReferenceInfos = program.getVariableReferenceInfos();
         for(var statement : program.getGraph().getAllStatements()) {
            Collection<VariableRef> definedVars = variableReferenceInfos.getDefinedVars(statement);
            Collection<VariableRef> usedVars = variableReferenceInfos.getUsedVars(statement);
            if (definedVars != null && definedVars.size() > 0) {
               for (VariableRef definedVar : definedVars) {
                  for (VariableRef usedVar : usedVars) {
                     incScore(definedVar, usedVar);
                  }
               }
            }
         }
      }

      private void incScore(VariableRef definedVar, VariableRef usedVar) {
         setScore(definedVar, usedVar, getScore(definedVar, usedVar) + 1);
         setScore(usedVar, definedVar, getScore(usedVar, definedVar) + 1);
      }

      private void setScore(VariableRef var1, VariableRef var2, Integer score) {
         scores.computeIfAbsent(var1, k -> new LinkedHashMap<>()).put(var2, score);
      }

      public Integer getScore(VariableRef var1, VariableRef var2) {
         Map<VariableRef, Integer> varScores = scores.get(var1);
         if(varScores == null) {
            return 0;
         }
         Integer score = varScores.get(var2);
         if(score == null) {
            return 0;
         }
         return score;
      }

   }

   /**
    * Scores for coalescing any pair of live range equivalence classes.
    * Scores are higher when a variable in one class is an lvalue and a variable in the other an rvalue in an assignment.
    */
   private static class CoalesceLiveRangeEquivalenceClassScores {

      /** Maps var to scores for coalescing with other LiveRangeEquivalenceClass */
      private Map<LiveRangeEquivalenceClass, Map<LiveRangeEquivalenceClass, Integer>> scores;

      public CoalesceLiveRangeEquivalenceClassScores(Program program, CoalesceVarScores varScores) {
         this.scores = new LinkedHashMap<>();
         LiveRangeEquivalenceClassSet liveRangeEquivalenceClassSet = program.getLiveRangeEquivalenceClassSet();
         List<LiveRangeEquivalenceClass> allEcs = liveRangeEquivalenceClassSet.getEquivalenceClasses();

         Map<VariableRef, LiveRangeEquivalenceClass> varToEc = new HashMap<>();
         for(LiveRangeEquivalenceClass ec : allEcs) {
            for(VariableRef var : ec.getVariables()) {
               varToEc.put(var, ec);
            }
         }

         Map<LiveRangeEquivalenceClass, Map<LiveRangeEquivalenceClass, Integer>> rawScores = new HashMap<>();
         for(Map.Entry<VariableRef, Map<VariableRef, Integer>> entry1 : varScores.scores.entrySet()) {
            VariableRef var1 = entry1.getKey();
            LiveRangeEquivalenceClass ec1 = varToEc.get(var1);
            if(ec1 == null) continue;
            for(Map.Entry<VariableRef, Integer> entry2 : entry1.getValue().entrySet()) {
               VariableRef var2 = entry2.getKey();
               Integer score = entry2.getValue();
               if(score == null || score <= 0) continue;
               LiveRangeEquivalenceClass ec2 = varToEc.get(var2);
               if(ec2 == null || ec1.equals(ec2)) continue;
               rawScores.computeIfAbsent(ec1, k -> new HashMap<>())
                     .merge(ec2, score, Integer::sum);
            }
         }

         for(LiveRangeEquivalenceClass ec1 : allEcs) {
            Map<LiveRangeEquivalenceClass, Integer> ec1Raw = rawScores.get(ec1);
            if(ec1Raw == null) continue;
            for(Map.Entry<LiveRangeEquivalenceClass, Integer> entry : ec1Raw.entrySet()) {
               if(entry.getValue() != null && entry.getValue() > 0) {
                  setScore(ec1, entry.getKey(), entry.getValue());
               }
            }
         }
      }

      private void setScore(LiveRangeEquivalenceClass ec1, LiveRangeEquivalenceClass ec2, Integer score) {
         scores.computeIfAbsent(ec1, k -> new LinkedHashMap<>()).put(ec2, score);
      }

      public Integer getScore(LiveRangeEquivalenceClass ec1, LiveRangeEquivalenceClass ec2) {
         Map<LiveRangeEquivalenceClass, Integer> varScores = scores.get(ec1);
         if(varScores == null) {
            return 0;
         }
         Integer score = varScores.get(ec2);
         if(score == null) {
            return 0;
         }
         return score;
      }

      /**
       * Get all candidate pairs of live range equivalence classes for coalescing with a positive score.
       * The returned list is sorted so the candidates with the highest score are first.
       *
       * @return candidate pairs of live range equivalence classes for coalescing with a positive score
       */
      public List<Pass4MemoryCoalesce.LiveRangeEquivalenceClassCoalesceCandidate> getCoalesceCandidates() {
         Set<Pass4MemoryCoalesce.LiveRangeEquivalenceClassCoalesceCandidate> candidateSet = new LinkedHashSet<>();
         for(Map.Entry<LiveRangeEquivalenceClass, Map<LiveRangeEquivalenceClass, Integer>> ec1Entry : scores.entrySet()) {
            LiveRangeEquivalenceClass ec1 = ec1Entry.getKey();
            for(Map.Entry<LiveRangeEquivalenceClass, Integer> ec2Entry : ec1Entry.getValue().entrySet()) {
               candidateSet.add(new Pass4MemoryCoalesce.LiveRangeEquivalenceClassCoalesceCandidate(ec1, ec2Entry.getKey(), ec2Entry.getValue()));
            }
         }
         ArrayList<Pass4MemoryCoalesce.LiveRangeEquivalenceClassCoalesceCandidate> candidates = new ArrayList<>(candidateSet);
         candidates.sort((o1, o2) -> (o2.getScore() - o1.getScore()));
         return candidates;
      }
   }

}
