package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.asm.AsmLabel;
import dk.camelot64.kickc.asm.AsmProgram;

import java.util.*;

/**
 * Shared per-round cache and dirty-scope tracker for Pass 5 ASM optimizations.
 * Avoids redundant line flattening, alias resolver construction, and clean-scope rescans.
 */
public class Pass5RoundContext {

   private final AsmProgram asmProgram;
   private List<Pass5AsmOptimization.LineRef> allLines;
   private Map<String, Pass5AsmOptimization.CanonicalResolver> scopeResolvers;
   private Map<String, List<Pass5AsmOptimization.LineRef>> scopeLinesMap;
   private Map<String, Integer> labelIndices;
   private boolean structureValid = false;
   private boolean labelsValid = false;
   private Set<String> activeDirtyScopes = null;
   private final Set<String> nextRoundDirtyScopes = new HashSet<>();
   private boolean markAllNextRound = false;
   private boolean stepRecordedScopeChange = false;

   public Pass5RoundContext(AsmProgram asmProgram) {
      this.asmProgram = asmProgram;
   }

   public List<Pass5AsmOptimization.LineRef> getAllLines() {
      if(!structureValid || allLines == null) {
         allLines = Pass5AsmOptimization.flattenLines(asmProgram);
         structureValid = true;
      }
      return allLines;
   }

   public Map<String, Pass5AsmOptimization.CanonicalResolver> getScopeResolvers() {
      if(!structureValid || scopeResolvers == null) {
         scopeResolvers = Pass5AsmOptimization.buildScopeResolvers(getAllLines());
      }
      return scopeResolvers;
   }

   public Map<String, List<Pass5AsmOptimization.LineRef>> getScopeLinesMap() {
      if(!structureValid || scopeLinesMap == null) {
         List<Pass5AsmOptimization.LineRef> lines = getAllLines();
         Map<String, List<Pass5AsmOptimization.LineRef>> map = new LinkedHashMap<>();
         for(Pass5AsmOptimization.LineRef ref : lines) {
            String scope = ref.scope != null ? ref.scope : "";
            map.computeIfAbsent(scope, k -> new ArrayList<>()).add(ref);
         }
         scopeLinesMap = map;
      }
      return scopeLinesMap;
   }

   public Map<String, Integer> getLabelIndices() {
      if(!structureValid || !labelsValid || labelIndices == null) {
         List<Pass5AsmOptimization.LineRef> lines = getAllLines();
         Map<String, Integer> map = new HashMap<>();
         for(int i = 0; i < lines.size(); i++) {
            Pass5AsmOptimization.LineRef ref = lines.get(i);
            if(ref.line instanceof AsmLabel lbl) {
               String label = lbl.getLabel();
               if(label != null && !label.startsWith("!")) {
                  String scope = ref.scope != null ? ref.scope : "";
                  map.putIfAbsent(scope + "::" + label, i);
               }
            }
         }
         labelIndices = map;
         labelsValid = true;
      }
      return labelIndices;
   }

   public boolean isScopeDirty(String scope) {
      return activeDirtyScopes == null || activeDirtyScopes.contains(scope != null ? scope : "");
   }

   public void markScopeDirty(String scope) {
      String key = (scope != null) ? scope : "";
      nextRoundDirtyScopes.add(key);
      if(activeDirtyScopes != null) {
         activeDirtyScopes.add(key);
      }
      scopeLinesMap = null;
      stepRecordedScopeChange = true;
   }

   public void markAllScopesDirty() {
      markAllNextRound = true;
      activeDirtyScopes = null;
      scopeLinesMap = null;
      stepRecordedScopeChange = true;
   }

   public void invalidateStructure() {
      structureValid = false;
      labelsValid = false;
      allLines = null;
      scopeResolvers = null;
      scopeLinesMap = null;
      labelIndices = null;
   }

   public void invalidateLabels() {
      labelsValid = false;
      labelIndices = null;
   }

   public void resetStepTracking() {
      stepRecordedScopeChange = false;
   }

   public boolean hasStepRecordedScopeChange() {
      return stepRecordedScopeChange;
   }

   public void advanceRound() {
      if(markAllNextRound) {
         activeDirtyScopes = null;
      } else {
         activeDirtyScopes = new HashSet<>(nextRoundDirtyScopes);
      }
      nextRoundDirtyScopes.clear();
      markAllNextRound = false;
   }
}
