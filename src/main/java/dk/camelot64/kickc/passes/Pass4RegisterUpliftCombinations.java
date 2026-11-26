package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.asm.AsmChunk;
import dk.camelot64.kickc.asm.AsmProgram;
import dk.camelot64.kickc.fragment.AsmFragmentTemplate;
import dk.camelot64.kickc.fragment.synthesis.AsmFragmentTemplateSynthesizer;
import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.model.values.VariableRef;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/*** Attempt to uplift live range equivalence classes to registers (instead of ZP) in each scope */
public class Pass4RegisterUpliftCombinations extends Pass2Base {

   public Pass4RegisterUpliftCombinations(Program program) {
      super(program);
   }

   /**
    * Iterate combinations and choose the combination that provides the best ASM score.
    * Stores the best combination directly in the {@link LiveRangeEquivalenceClassSet}.
    *
    * @param combinationIterator The combination iterator used for supplying different register allocations to test
    * @param maxCombinations The maximal number of combinations to test. It the iterator has more combinations he rest is skipped (and a message logged)
    * @param unknownFragments Receives any unknown ASM fragments encountered during the combination search
    * @param scope The scope where the variables are being tested. (Only used for logging)
    * @param program The program to test (used for accessing global data structures)
    */
   public static class CachedBlockScores {
      public final Map<Graph.Block, Integer> blockScores = new HashMap<>();
      public final Map<Graph.Block, Integer> blockBytes = new HashMap<>();
      public int totalScore = 0;
      public int totalBytes = 0;

      public void init(Program program) {
         double[] stmtMultipliers = getStmtMultipliers(program);
         Pass4DirectTrialEvaluator.FastClobberIndex clobberIndex = Pass4DirectTrialEvaluator.getOrBuildClobberIndex(program);
         clobberIndex.refreshFromVariables();
         blockScores.clear();
         blockBytes.clear();
         totalScore = 0;
         totalBytes = 0;
         Pass4DirectTrialEvaluator.computeBlockScores(
               program,
               program.getGraph().getAllBlocks(),
               stmtMultipliers,
               blockScores,
               blockBytes,
               clobberIndex,
               true);
         for(Integer s : blockScores.values()) {
            totalScore += s;
         }
         for(Integer b : blockBytes.values()) {
            totalBytes += b;
         }
      }

      public int getOutsideScore(Set<Graph.Block> blocksToRegenerate) {
         int outsideScore = totalScore;
         for(Graph.Block b : blocksToRegenerate) {
            Integer s = blockScores.get(b);
            if(s != null) {
               outsideScore -= s;
            }
         }
         return outsideScore;
      }

      public void updateBlocks(Set<Graph.Block> blocksToRegenerate, Program program) {
         double[] stmtMultipliers = getStmtMultipliers(program);
         Pass4DirectTrialEvaluator.FastClobberIndex clobberIndex = Pass4DirectTrialEvaluator.getOrBuildClobberIndex(program);
         for(Graph.Block b : blocksToRegenerate) {
            Integer oldS = blockScores.get(b);
            if(oldS != null) {
               totalScore -= oldS;
            }
            Integer oldB = blockBytes.get(b);
            if(oldB != null) {
               totalBytes -= oldB;
            }
         }
         Map<Graph.Block, Integer> updatedScores = new HashMap<>();
         Map<Graph.Block, Integer> updatedBytes = new HashMap<>();
         Pass4DirectTrialEvaluator.computeBlockScores(
               program,
               blocksToRegenerate,
               stmtMultipliers,
               updatedScores,
               updatedBytes,
               clobberIndex,
               true);
         for(Graph.Block b : blocksToRegenerate) {
            int newS = updatedScores.getOrDefault(b, 0);
            int newB = updatedBytes.getOrDefault(b, 0);
            blockScores.put(b, newS);
            blockBytes.put(b, newB);
            totalScore += newS;
            totalBytes += newB;
         }
      }
   }

   public static void chooseBestUpliftCombination(
         RegisterCombinationIterator combinationIterator, int maxCombinations,
         Set<String> unknownFragments,
         ScopeRef scope,
         Program program
   ) {
      chooseBestUpliftCombination(combinationIterator, maxCombinations, unknownFragments, scope, program, (CachedBlockScores) null);
   }

   public static class UpliftTrialResult {
      public final RegisterCombination combination;
      public final int score;
      public final int bytes;
      public final int upliftedRegCount;
      public final boolean success;

      public UpliftTrialResult(RegisterCombination combination, int score, boolean success) {
         this(combination, score, Integer.MAX_VALUE, 0, success);
      }

      public UpliftTrialResult(RegisterCombination combination, int score, int bytes, int upliftedRegCount, boolean success) {
         this.combination = combination;
         this.score = score;
         this.bytes = bytes;
         this.upliftedRegCount = upliftedRegCount;
         this.success = success;
      }
   }

   public static UpliftTrialResult evaluateCombination(
         RegisterCombination combination,
         Program program,
         Set<String> unknownFragments,
         ScopeRef scope,
         Set<Graph.Block> blocksToRegenerate,
         int outsideScore) {
      Pass4DirectTrialEvaluator.FastClobberIndex clobberIndex = Pass4DirectTrialEvaluator.getOrBuildClobberIndex(program);
      clobberIndex.refreshFromVariables();
      return evaluateCombination(
            combination,
            program,
            unknownFragments,
            scope,
            blocksToRegenerate,
            outsideScore,
            getStmtMultipliers(program),
            clobberIndex,
            false,
            true);
   }

   public static UpliftTrialResult evaluateCombination(
         RegisterCombination combination,
         Program program,
         Set<String> unknownFragments,
         ScopeRef scope,
         Set<Graph.Block> blocksToRegenerate,
         int outsideScore,
         Map<Integer, Double> multipliers) {
      Pass4DirectTrialEvaluator.FastClobberIndex clobberIndex = Pass4DirectTrialEvaluator.getOrBuildClobberIndex(program);
      clobberIndex.refreshFromVariables();
      double[] stmtMultipliers;
      boolean useBaselineCache;
      if(multipliers == null) {
         stmtMultipliers = null;
         useBaselineCache = false;
      } else if(multipliers == program.getUpliftProgramCache().loopMultipliers) {
         stmtMultipliers = getStmtMultipliers(program);
         useBaselineCache = true;
      } else {
         stmtMultipliers = new double[clobberIndex.numStmts];
         Arrays.fill(stmtMultipliers, 10.0);
         for(Map.Entry<Integer, Double> e : multipliers.entrySet()) {
            Integer k = e.getKey();
            Double v = e.getValue();
            if(k != null && k >= 0 && k < stmtMultipliers.length && v != null) {
               stmtMultipliers[k] = v;
            }
         }
         useBaselineCache = false;
      }
      return evaluateCombination(combination, program, unknownFragments, scope, blocksToRegenerate, outsideScore, stmtMultipliers, clobberIndex, false, useBaselineCache);
   }

   private static UpliftTrialResult evaluateCombination(
         RegisterCombination combination,
         Program program,
         Set<String> unknownFragments,
         ScopeRef scope,
         Set<Graph.Block> blocksToRegenerate,
         int outsideScore,
         double[] stmtMultipliers,
         Pass4DirectTrialEvaluator.FastClobberIndex clobberIndex,
         boolean precheckedConflictFree,
         boolean useBaselineCache) {
      countAttempts.incrementAndGet();
      // Check the register allocation for whether any register is being allocated to two variables with overlapping live ranges
      if(!precheckedConflictFree && isAllocationOverlapping(program, combination)) {
         return new UpliftTrialResult(combination, Integer.MAX_VALUE, false);
      }

      int overrideCount = 0;
      for(LiveRangeEquivalenceClass ec : combination.getAllocation().keySet()) {
         overrideCount += ec.getVariables().size();
      }
      Map<VariableRef, Registers.Register> override = HashMap.newHashMap(overrideCount);
      int[] overrideVarIds = new int[overrideCount];
      byte[] overrideRegMasks = new byte[overrideCount];
      Registers.Register[] overrideRegs = new Registers.Register[overrideCount];
      int patchedCount = 0;
      for(Map.Entry<LiveRangeEquivalenceClass, Registers.Register> entry : combination.getAllocation().entrySet()) {
         Registers.Register reg = entry.getValue();
         byte regMask = Pass4DirectTrialEvaluator.toRegMask(reg);
         for(VariableRef varRef : entry.getKey().getVariables()) {
            override.put(varRef, reg);
            Integer varId = clobberIndex.varIdMap.get(varRef);
            if(varId != null) {
               overrideVarIds[patchedCount] = varId;
               overrideRegMasks[patchedCount] = regMask;
               overrideRegs[patchedCount] = reg;
               patchedCount++;
            }
         }
      }

      Pass4DirectTrialEvaluator.FastClobberIndex.LocalState localState = clobberIndex.acquireLocalState();
      byte[] localRegMasks = localState.regMasks;
      Registers.Register[] localAllocs = localState.allocations;
      boolean[] localOverridden = localState.overridden;
      for(int i = 0; i < patchedCount; i++) {
         int id = overrideVarIds[i];
         localRegMasks[id] = overrideRegMasks[i];
         localAllocs[id] = overrideRegs[i];
         localOverridden[id] = true;
      }

      Variable.setThreadAllocationOverride(override);
      try {
         Pass4DirectTrialEvaluator.TrialEvalResult evalResult = Pass4DirectTrialEvaluator.evaluateBlocks(
               program, blocksToRegenerate, stmtMultipliers, unknownFragments, clobberIndex, localState, useBaselineCache);
         if(!evalResult.success()) {
            return new UpliftTrialResult(combination, Integer.MAX_VALUE, false);
         }

         int combinationScore = outsideScore + evalResult.score();
         int asmBytes = evalResult.bytes();
         int cpuRegs = 0;
         for(Registers.Register r : combination.getAllocation().values()) {
            if(r != null && !(r instanceof Registers.RegisterZpMem) && !(r instanceof Registers.RegisterMainMem)) {
               cpuRegs++;
            }
         }
         return new UpliftTrialResult(combination, combinationScore, asmBytes, cpuRegs, true);
      } finally {
         Variable.setThreadAllocationOverride(null);
         byte[] base = clobberIndex.baseCpuRegMask;
         Registers.Register[] baseAllocs = clobberIndex.baseAllocations;
         for(int i = 0; i < patchedCount; i++) {
            int id = overrideVarIds[i];
            localRegMasks[id] = base[id];
            localAllocs[id] = baseAllocs[id];
            localOverridden[id] = false;
         }
      }
   }

   public static void chooseBestUpliftCombination(
         RegisterCombinationIterator combinationIterator, int maxCombinations,
         Set<String> unknownFragments,
         ScopeRef scope,
         Program program,
         CachedBlockScores cache
   ) {
      if(cache == null) {
         cache = new CachedBlockScores();
         cache.init(program);
      }
      Set<Graph.Block> blocksToRegenerate = getBlocksToRegenerate(program, scope, combinationIterator.getEquivalenceClasses());
      int outsideScore = cache.getOutsideScore(blocksToRegenerate);

      // GEN-05: Pre-filter overlapping combinations so the maxCombinations budget is spent on conflict-free allocations
      program.getLiveRangeInterference();
      List<RegisterCombination> combinationsToTest = new ArrayList<>();
      int maxScanned = Math.max(maxCombinations * 10, 200);
      int scanned = 0;
      while(combinationIterator.hasNext() && combinationsToTest.size() < maxCombinations && scanned < maxScanned) {
         RegisterCombination cand = combinationIterator.next();
         scanned++;
         if(!isAllocationOverlapping(program, cand)) {
            combinationsToTest.add(cand);
         }
      }

      if(combinationsToTest.isEmpty()) return;

      int countCombinations = combinationsToTest.size();
      int bestScore = Integer.MAX_VALUE;
      int bestBytes = Integer.MAX_VALUE;
      int bestUpliftedCount = -1;
      RegisterCombination bestCombination = null;

      List<UpliftTrialResult> results;
      double[] stmtMultipliers = getStmtMultipliers(program);
      Pass4DirectTrialEvaluator.FastClobberIndex clobberIndex;
      if(combinationsToTest.size() > 1) {
         // Pre-initialize lazy analyses on the main thread before parallel evaluation
         program.getLiveRangeInterference();
         program.getPhiTransitions();
         program.getSymbolInfos();
         program.getStatementInfos();
         program.getLiveRangeVariablesEffective();
         program.getLoopSet();
         program.getVariableReferenceInfos();
         program.getCallGraph();
         Pass4DirectTrialEvaluator.getCpuSynthesizer(program);
         clobberIndex = Pass4DirectTrialEvaluator.getOrBuildClobberIndex(program);
         results = combinationsToTest.parallelStream()
               .map(c -> evaluateCombination(c, program, unknownFragments, scope, blocksToRegenerate, outsideScore, stmtMultipliers, clobberIndex, true, true))
               .toList();
      } else {
         clobberIndex = Pass4DirectTrialEvaluator.getOrBuildClobberIndex(program);
         results = Collections.singletonList(evaluateCombination(combinationsToTest.get(0), program, unknownFragments, scope, blocksToRegenerate, outsideScore, stmtMultipliers, clobberIndex, true, true));
      }

      for(UpliftTrialResult res : results) {
         if(program.getLog().isVerboseUplift()) {
            if(res.success) {
               program.getLog().append("Uplift attempt [" + scope + "] " + res.score + " allocation: " + res.combination.toString());
            }
         }
         // GEN-05: Break score ties by smaller code size (bytes), then higher CPU register count (saving ZP)
         if(res.success && (res.score < bestScore
               || (res.score == bestScore && (res.bytes < bestBytes
                     || (res.bytes == bestBytes && res.upliftedRegCount > bestUpliftedCount))))) {
            bestScore = res.score;
            bestBytes = res.bytes;
            bestUpliftedCount = res.upliftedRegCount;
            bestCombination = res.combination;
         }
      }

      if(bestCombination != null) {
         boolean changed = false;
         LiveRangeEquivalenceClassSet ecSet = program.getLiveRangeEquivalenceClassSet();
         for(Map.Entry<LiveRangeEquivalenceClass, Registers.Register> entry : bestCombination.getAllocation().entrySet()) {
            LiveRangeEquivalenceClass ec = entry.getKey();
            VariableRef var = ec.getVariables().get(0);
            LiveRangeEquivalenceClass globalEc = ecSet.getEquivalenceClass(var);
            Registers.Register currentReg = (globalEc != null) ? globalEc.getRegister() : ec.getRegister();
            if(!Objects.equals(currentReg, entry.getValue())) {
               changed = true;
               break;
            }
         }
         if(changed) {
            // Save the best combination in the equivalence class
            bestCombination.store(ecSet);
            bestCombination.allocate(program);
            if(program.getLiveRangeInterference() != null) program.getLiveRangeInterference().updateCombination(bestCombination);
            clobberIndex.updateCombination(bestCombination);
            program.getLog().append("Uplifting [" + scope + "] best " + bestScore + " combination " + bestCombination.toString());
            cache.updateBlocks(blocksToRegenerate, program);
         }
      }
      if(combinationIterator.hasNext()) {
         program.getLog().append("Limited combination testing to " + countCombinations + " combinations of " + combinationIterator.getNumIterations() + " possible.");
      }
   }

   public static boolean generateCombinationAsm(
         RegisterCombination combination,
         Program program,
         Set<String> unknownFragments,
         ScopeRef scope) {
      return generateCombinationAsm(combination, program, unknownFragments, scope, null, null);
   }

   public static class UpliftProgramCache {
      public volatile Map<Integer, Double> loopMultipliers;
      public volatile double[] stmtMultipliers;
      public volatile Map<VariableRef, List<Statement>> varStatements;
      public volatile Map<ScopeRef, List<Graph.Block>> blocksByScope;
      public volatile Map<dk.camelot64.kickc.model.values.LabelRef, List<Graph.Block>> predecessors;
      public volatile Map<dk.camelot64.kickc.model.values.LabelRef, Graph.Block> blocksByLabel;
      public volatile AsmFragmentTemplateSynthesizer cpuSynthesizer;
      public final ConcurrentHashMap<String, AsmFragmentTemplate> fragmentTemplates = new ConcurrentHashMap<>();
      public volatile Pass4DirectTrialEvaluator.FastClobberIndex clobberIndex;
   }

   public static final AtomicLong countAttempts = new AtomicLong(0);

   public static boolean generateCombinationAsm(
         RegisterCombination combination,
         Program program,
         Set<String> unknownFragments,
         ScopeRef scope,
         Set<Graph.Block> blocksToRegenerate,
         Map<Graph.Block, List<AsmChunk>> cachedBlockChunks) {
      if(blocksToRegenerate == null) {
         blocksToRegenerate = getBlocksToRegenerate(program, scope, combination.getAllocation().keySet());
      }
      UpliftTrialResult res = evaluateCombination(combination, program, unknownFragments, scope, blocksToRegenerate, 0);
      return res.success;
   }

   static double[] getStmtMultipliers(Program program) {
      UpliftProgramCache cache = program.getUpliftProgramCache();
      double[] arr = cache.stmtMultipliers;
      if(arr != null) {
         return arr;
      }
      synchronized(cache) {
         arr = cache.stmtMultipliers;
         if(arr != null) {
            return arr;
         }
         Collection<Statement> allStatements = program.getGraph().getAllStatements();
         int maxIdx = -1;
         for(Statement stmt : allStatements) {
            Integer idx = stmt.getIndex();
            if(idx != null && idx > maxIdx) {
               maxIdx = idx;
            }
         }
         arr = new double[maxIdx + 1];
         Arrays.fill(arr, 10.0);
         NaturalLoopSet loopSet = program.getLoopSet();
         StatementInfos stmtInfos = program.getStatementInfos();
         for(Statement stmt : allStatements) {
            Integer idx = stmt.getIndex();
            if(idx != null && idx >= 0) {
               Graph.Block block = stmtInfos.getBlock(idx);
               int depth = loopSet.getMaxLoopDepth(block.getLabel());
               arr[idx] = Math.pow(10, depth);
            }
         }
         cache.stmtMultipliers = arr;
         return arr;
      }
   }

   private static Map<Integer, Double> getLoopMultipliers(Program program) {
      UpliftProgramCache cache = program.getUpliftProgramCache();
      Map<Integer, Double> map = cache.loopMultipliers;
      if(map != null) {
         return map;
      }
      synchronized(cache) {
         map = cache.loopMultipliers;
         if(map != null) {
            return map;
         }
         map = new HashMap<>();
         NaturalLoopSet loopSet = program.getLoopSet();
         for(Statement stmt : program.getGraph().getAllStatements()) {
            Graph.Block block = program.getStatementInfos().getBlock(stmt.getIndex());
            int depth = loopSet.getMaxLoopDepth(block.getLabel());
            map.put(stmt.getIndex(), Math.pow(10, depth));
         }
         cache.loopMultipliers = map;
         return map;
      }
   }

   /**
    * Get the score for the generated ASM program.
    * Programs that take less cycles to execute have lower scores.
    * In practice the score is calculated by multiplying cycles of ASM instructions with
    * an estimate of the invocation count based on the loop depth of the instructions (10^depth).
    *
    * @param program The program containing the ASM to check
    * @return The score of the ASM
    */
   public static int getAsmScore(Program program) {
      return getAsmScore(program, null);
   }

   public static int getAsmScore(Program program, AsmProgram asm) {
      return getAsmScore(program, asm, getStmtMultipliers(program));
   }

   private static int getAsmScore(Program program, AsmProgram asm, double[] stmtMultipliers) {
      int score = 0;
      if(asm == null) asm = program.getAsm();
      for(AsmChunk asmChunk : asm.getChunks()) {
         double asmChunkCycles = asmChunk.getCycles();
         if(asmChunkCycles > 0) {
            Integer statementIdx = asmChunk.getStatementIdx();
            double mult = (statementIdx != null && statementIdx >= 0 && statementIdx < stmtMultipliers.length)
                  ? stmtMultipliers[statementIdx]
                  : 10.0;
            score += asmChunkCycles * mult;
         }
      }
      return score;
   }

   public static int getAsmScore(Program program, AsmProgram asm, Map<Integer, Double> multipliers) {
      int score = 0;
      if(asm == null) asm = program.getAsm();
      for(AsmChunk asmChunk : asm.getChunks()) {
         double asmChunkCycles = asmChunk.getCycles();
         if(asmChunkCycles > 0) {
            Integer statementIdx = asmChunk.getStatementIdx();
            Double mult = statementIdx == null ? 10.0 : multipliers.get(statementIdx);
            score += asmChunkCycles * (mult == null ? 10.0 : mult);
         }
      }
      return score;
   }

   private static Map<VariableRef, List<Statement>> getVarStatements(Program program) {
      UpliftProgramCache cache = program.getUpliftProgramCache();
      Map<VariableRef, List<Statement>> map = cache.varStatements;
      if(map != null) {
         return map;
      }
      synchronized(cache) {
         map = cache.varStatements;
         if(map != null) {
            return map;
         }
         map = new HashMap<>();
         VariableReferenceInfos refInfos = program.getVariableReferenceInfos();
         LiveRangeVariablesEffective liveRanges = program.getLiveRangeVariablesEffective();
         for(Statement statement : program.getGraph().getAllStatements()) {
            Set<VariableRef> stmVars = new HashSet<>();
            stmVars.addAll(refInfos.getReferencedVars(statement));
            stmVars.addAll(liveRanges.getAliveEffective(statement));
            for(VariableRef v : stmVars) {
               map.computeIfAbsent(v, k -> new ArrayList<>()).add(statement);
            }
         }
         cache.varStatements = map;
         return map;
      }
   }

   private static Map<ScopeRef, List<Graph.Block>> getBlocksByScope(Program program) {
      UpliftProgramCache cache = program.getUpliftProgramCache();
      Map<ScopeRef, List<Graph.Block>> map = cache.blocksByScope;
      if(map != null) {
         return map;
      }
      synchronized(cache) {
         map = cache.blocksByScope;
         if(map != null) {
            return map;
         }
         map = new HashMap<>();
         for(Graph.Block b : program.getGraph().getAllBlocks()) {
            if(b.getScope() != null) {
               map.computeIfAbsent(b.getScope(), k -> new ArrayList<>()).add(b);
            }
         }
         cache.blocksByScope = map;
         return map;
      }
   }

   private static Map<dk.camelot64.kickc.model.values.LabelRef, List<Graph.Block>> getPredecessorsMap(Program program) {
      UpliftProgramCache cache = program.getUpliftProgramCache();
      Map<dk.camelot64.kickc.model.values.LabelRef, List<Graph.Block>> map = cache.predecessors;
      if(map != null) {
         return map;
      }
      synchronized(cache) {
         map = cache.predecessors;
         if(map != null) {
            return map;
         }
         map = new HashMap<>();
         for(Graph.Block other : program.getGraph().getAllBlocks()) {
            if(other.getDefaultSuccessor() != null) {
               map.computeIfAbsent(other.getDefaultSuccessor(), k -> new ArrayList<>()).add(other);
            }
            if(other.getConditionalSuccessor() != null) {
               map.computeIfAbsent(other.getConditionalSuccessor(), k -> new ArrayList<>()).add(other);
            }
            if(other.getCallSuccessor() != null) {
               map.computeIfAbsent(other.getCallSuccessor(), k -> new ArrayList<>()).add(other);
            }
         }
         cache.predecessors = map;
         return map;
      }
   }

   private static Map<dk.camelot64.kickc.model.values.LabelRef, Graph.Block> getBlocksByLabel(Program program) {
      UpliftProgramCache cache = program.getUpliftProgramCache();
      Map<dk.camelot64.kickc.model.values.LabelRef, Graph.Block> map = cache.blocksByLabel;
      if(map != null) {
         return map;
      }
      synchronized(cache) {
         map = cache.blocksByLabel;
         if(map != null) {
            return map;
         }
         map = new HashMap<>();
         for(Graph.Block b : program.getGraph().getAllBlocks()) {
            map.put(b.getLabel(), b);
         }
         cache.blocksByLabel = map;
         return map;
      }
   }

   public static Set<Graph.Block> getBlocksToRegenerate(
         Program program,
         ScopeRef scope,
         Collection<LiveRangeEquivalenceClass> equivalenceClasses) {
      Set<Graph.Block> blocks = new LinkedHashSet<>();
      if(equivalenceClasses != null && !equivalenceClasses.isEmpty()) {
         Map<VariableRef, List<Statement>> varStatements = getVarStatements(program);
         for(LiveRangeEquivalenceClass ec : equivalenceClasses) {
            for(VariableRef varRef : ec.getVariables()) {
               List<Statement> stmts = varStatements.get(varRef);
               if(stmts != null) {
                  for(Statement stmt : stmts) {
                     Graph.Block b = program.getStatementInfos().getBlock(stmt.getIndex());
                     if(b != null) {
                        blocks.add(b);
                     }
                  }
               }
            }
         }
      }
      if(blocks.isEmpty() && scope != null) {
         List<Graph.Block> scopeBlocks = getBlocksByScope(program).get(scope);
         if(scopeBlocks != null) {
            blocks.addAll(scopeBlocks);
         }
      }
      Map<dk.camelot64.kickc.model.values.LabelRef, List<Graph.Block>> predMap = getPredecessorsMap(program);
      Map<dk.camelot64.kickc.model.values.LabelRef, Graph.Block> blockMap = getBlocksByLabel(program);
      Set<Graph.Block> neighbors = new LinkedHashSet<>();
      for(Graph.Block b : blocks) {
         List<Graph.Block> preds = predMap.get(b.getLabel());
         if(preds != null) {
            neighbors.addAll(preds);
         }
         for(dk.camelot64.kickc.model.values.LabelRef succ : b.getSuccessors()) {
            Graph.Block succBlock = blockMap.get(succ);
            if(succBlock != null) {
               neighbors.add(succBlock);
            }
         }
      }
      blocks.addAll(neighbors);
      return blocks;
   }

   /**
    * Check the register allocation for whether a is register being allocated to two variables with overlapping live ranges
    *
    * @param program The program
    * @return true if the register allocation contains an overlapping allocation. false otherwise.
    */
   public static boolean isAllocationOverlapping(Program program) {
      return program.getLiveRangeInterference().isAllocationOverlapping(null);
   }

   public static boolean isAllocationOverlapping(Program program, RegisterCombination combination) {
      return program.getLiveRangeInterference().isAllocationOverlapping(combination);
   }

   public static boolean isAllocationOverlappingSlow(Program program, RegisterCombination combination) {
      if(combination == null) {
         for(var statement : program.getGraph().getAllStatements()) {
            if(isStatementAllocationOverlapping(program, statement)) {
               return true;
            }
         }
         return false;
      }
      Map<VariableRef, List<Statement>> varStatements = getVarStatements(program);
      Set<Statement> statementsToCheck = new HashSet<>();
      for(LiveRangeEquivalenceClass ec : combination.getAllocation().keySet()) {
         for(VariableRef v : ec.getVariables()) {
            List<Statement> stmts = varStatements.get(v);
            if(stmts != null) {
               statementsToCheck.addAll(stmts);
            }
         }
      }
      for(Statement statement : statementsToCheck) {
         if(isStatementAllocationOverlapping(program, statement)) {
            return true;
         }
      }
      return false;
   }

   /**
    * Determine if a statement has an overlapping register allocation
    *
    * @param program The program
    * @param statement The statement to check
    * @return true if there is an overlapping register allocation
    */
   private static boolean isStatementAllocationOverlapping(Program program, Statement statement) {
      VariableReferenceInfos variableReferenceInfos = program.getVariableReferenceInfos();
      LiveRangeVariablesEffective.AliveCombinations aliveCombinations = program.getLiveRangeVariablesEffective().getAliveCombinations(statement);
      for(LiveRangeVariablesEffective.AliveCombination combination : aliveCombinations.getAll()) {
         LinkedHashMap<Registers.Register, LiveRangeEquivalenceClass> usedRegisters = new LinkedHashMap<>();
         Collection<VariableRef> aliveAtStmt = combination.getEffectiveAliveAtStmt();
         final Collection<VariableRef> stmtDefinedVars = variableReferenceInfos.getDefinedVars(statement);
         if(!aliveAtStmt.containsAll(stmtDefinedVars)) {
            // Also add the variables assigned by the statement. They are written to even if they are not used later - so they are used!
            aliveAtStmt = new LinkedHashSet<>(aliveAtStmt);
            aliveAtStmt.addAll(stmtDefinedVars);
         }
         Pass2AliasElimination.Aliases aliasesAtStmt = combination.getEffectiveAliasesAtStmt();
         for(VariableRef varRef : aliveAtStmt) {
            Variable var = program.getSymbolInfos().getVariable(varRef);
            Registers.Register allocation = var.getAllocation();
            LiveRangeEquivalenceClass allocationClass = usedRegisters.get(allocation);
            if(allocationClass != null && !allocationClass.contains(varRef)) {
               // Examine if the var is an alias of a var in the allocation class
               boolean overlap = true;
               if(aliasesAtStmt != null) {
                  Pass2AliasElimination.AliasSet aliasSet = aliasesAtStmt.findAliasSet(varRef);
                  if(aliasSet != null) {
                     for(VariableRef aliasVar : aliasSet.getVars()) {
                        if(allocationClass.contains(aliasVar)) {
                           overlap = false;
                        }
                     }
                  }
               }
               if(overlap) {
                  if(program.getLog().isVerboseUplift()) {
                     StringBuilder msg = new StringBuilder();
                     msg.append("Overlap register " + allocation + " in " + statement.toString(program, true));
                     program.getLog().append(msg.toString());
                  }
                  return true;
               }
            } else {
               LiveRangeEquivalenceClass varClass =
                     program.getLiveRangeEquivalenceClassSet().getEquivalenceClass(varRef);
               usedRegisters.put(allocation, varClass);
            }
         }
      }
      return false;
   }

   public CachedBlockScores performUplift(int maxCombinations) {
      // Test uplift combinations to find the best one.
      Set<String> unknownFragments = Collections.synchronizedSet(new LinkedHashSet<>());
      CachedBlockScores cache = new CachedBlockScores();
      cache.init(getProgram());

      List<RegisterUpliftScope> registerUpliftScopes = getProgram().getRegisterUpliftProgram().getRegisterUpliftScopes();
      for(RegisterUpliftScope upliftScope : registerUpliftScopes) {
         RegisterCombinationIterator combinationIterator = upliftScope.getCombinationIterator(getProgram().getRegisterPotentials());
         chooseBestUpliftCombination(
               combinationIterator,
               maxCombinations,
               unknownFragments,
               upliftScope.getScopeRef(),
               getProgram(),
               cache);
      }

      if(unknownFragments.size() > 0) {
         getLog().append("MISSING FRAGMENTS");
         List<String> sortedUnknown = new ArrayList<>(unknownFragments);
         Collections.sort(sortedUnknown);
         for(String unknownFragment : sortedUnknown) {
            getLog().append("  " + unknownFragment);
         }
      }
      if(getLog().isVerboseUplift()) {
         System.out.printf("      [Uplift Breakdown] %d attempts%n", countAttempts.get());
      }
      return cache;
   }

}
