package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.asm.AsmChunk;
import dk.camelot64.kickc.asm.AsmProgram;
import dk.camelot64.kickc.fragment.synthesis.AsmFragmentTemplateSynthesizer;
import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.model.values.VariableRef;

import java.util.*;

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
      public final Map<Graph.Block, List<AsmChunk>> cachedBlockChunks = new HashMap<>();
      public final Map<Graph.Block, Integer> blockScores = new HashMap<>();
      public int totalScore = 0;

      public void init(Program program) {
         new Pass4CodeGeneration(program, false, program.isWarnFragmentMissing(), true).generate(null, cachedBlockChunks, true);
         Map<Integer, Double> multipliers = getLoopMultipliers(program);
         blockScores.clear();
         totalScore = 0;
         for(Map.Entry<Graph.Block, List<AsmChunk>> entry : cachedBlockChunks.entrySet()) {
            int score = calculateBlockScore(entry.getValue(), multipliers);
            blockScores.put(entry.getKey(), score);
            totalScore += score;
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
         new Pass4CodeGeneration(program, false, program.isWarnFragmentMissing(), true).generate(blocksToRegenerate, cachedBlockChunks, true);
         Map<Integer, Double> multipliers = getLoopMultipliers(program);
         for(Graph.Block b : blocksToRegenerate) {
            Integer oldS = blockScores.get(b);
            if(oldS != null) {
               totalScore -= oldS;
            }
            int newS = calculateBlockScore(cachedBlockChunks.get(b), multipliers);
            blockScores.put(b, newS);
            totalScore += newS;
         }
      }

      private static int calculateBlockScore(List<AsmChunk> chunks, Map<Integer, Double> multipliers) {
         int score = 0;
         if(chunks != null) {
            for(AsmChunk chunk : chunks) {
               double cycles = chunk.getCycles();
               if(cycles > 0) {
                  Integer statementIdx = chunk.getStatementIdx();
                  Double mult = statementIdx == null ? 10.0 : multipliers.get(statementIdx);
                  score += (int) (cycles * (mult == null ? 10.0 : mult));
               }
            }
         }
         return score;
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
      public final boolean success;

      public UpliftTrialResult(RegisterCombination combination, int score, boolean success) {
         this.combination = combination;
         this.score = score;
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
      countAttempts++;
      // Check the register allocation for whether any register is being allocated to two variables with overlapping live ranges
      if(isAllocationOverlapping(program, combination)) {
         return new UpliftTrialResult(combination, Integer.MAX_VALUE, false);
      }

      Map<VariableRef, Registers.Register> override = new HashMap<>();
      for(Map.Entry<LiveRangeEquivalenceClass, Registers.Register> entry : combination.getAllocation().entrySet()) {
         Registers.Register reg = entry.getValue();
         for(VariableRef varRef : entry.getKey().getVariables()) {
            override.put(varRef, reg);
         }
      }

      Variable.setThreadAllocationOverride(override);
      try {
         AsmProgram asm;
         try {
            asm = new Pass4CodeGeneration(program, false, program.isWarnFragmentMissing(), true)
                  .generate(blocksToRegenerate, null, false);
         } catch(AsmFragmentTemplateSynthesizer.UnknownFragmentException e) {
            if(unknownFragments != null) {
               unknownFragments.add(e.getFragmentSignature());
            }
            return new UpliftTrialResult(combination, Integer.MAX_VALUE, false);
         }

         boolean hasClobberProblem = new Pass4AssertNoCpuClobber(program).hasClobberProblem(false, asm);
         if(hasClobberProblem) {
            return new UpliftTrialResult(combination, Integer.MAX_VALUE, false);
         }

         int combinationScore = outsideScore + getAsmScore(program, asm);
         return new UpliftTrialResult(combination, combinationScore, true);
      } finally {
         Variable.setThreadAllocationOverride(null);
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
      long tBlk = System.nanoTime();
      Set<Graph.Block> blocksToRegenerate = getBlocksToRegenerate(program, scope, combinationIterator.getEquivalenceClasses());
      long tBlkEnd = System.nanoTime();
      int outsideScore = cache.getOutsideScore(blocksToRegenerate);
      long tOutEnd = System.nanoTime();
      timeBlocksNs += (tBlkEnd - tBlk);
      timeOutsideNs += (tOutEnd - tBlkEnd);

      List<RegisterCombination> combinationsToTest = new ArrayList<>();
      while(combinationIterator.hasNext() && combinationsToTest.size() < maxCombinations) {
         combinationsToTest.add(combinationIterator.next());
      }

      if(combinationsToTest.isEmpty()) return;

      int countCombinations = combinationsToTest.size();
      int bestScore = Integer.MAX_VALUE;
      RegisterCombination bestCombination = null;

      List<UpliftTrialResult> results;
      if(combinationsToTest.size() > 1) {
         results = combinationsToTest.parallelStream()
               .map(c -> evaluateCombination(c, program, unknownFragments, scope, blocksToRegenerate, outsideScore))
               .toList();
      } else {
         results = Collections.singletonList(evaluateCombination(combinationsToTest.get(0), program, unknownFragments, scope, blocksToRegenerate, outsideScore));
      }

      for(UpliftTrialResult res : results) {
         if(program.getLog().isVerboseUplift()) {
            if(res.success) {
               program.getLog().append("Uplift attempt [" + scope + "] " + res.score + " allocation: " + res.combination.toString());
            }
         }
         if(res.success && res.score < bestScore) {
            bestScore = res.score;
            bestCombination = res.combination;
         }
      }

      if(bestCombination != null) {
         // Save the best combination in the equivalence class
         bestCombination.store(program.getLiveRangeEquivalenceClassSet());
         bestCombination.allocate(program);
         if(program.getLiveRangeInterference() != null) program.getLiveRangeInterference().updateCombination(bestCombination);
         program.getLog().append("Uplifting [" + scope + "] best " + bestScore + " combination " + bestCombination.toString());
         long tUp = System.nanoTime();
         cache.updateBlocks(blocksToRegenerate, program);
         timeUpdateNs += (System.nanoTime() - tUp);
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

   public static long timeOverlapNs = 0;
   public static long timeCodeGenNs = 0;
   public static long timeClobberNs = 0;
   public static long countAttempts = 0;
   public static long timeBlocksNs = 0;
   public static long timeIteratorNs = 0;
   public static long timeScoreNs = 0;
   public static long timeUpdateNs = 0;
   public static long timeOutsideNs = 0;

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

   private static Map<Integer, Double> loopMultipliersCache = null;
   private static Program loopMultipliersProgram = null;

   private static synchronized Map<Integer, Double> getLoopMultipliers(Program program) {
      if(loopMultipliersProgram == program && loopMultipliersCache != null) {
         return loopMultipliersCache;
      }
      Map<Integer, Double> map = new HashMap<>();
      NaturalLoopSet loopSet = program.getLoopSet();
      for(Statement stmt : program.getGraph().getAllStatements()) {
         Graph.Block block = program.getStatementInfos().getBlock(stmt.getIndex());
         int depth = loopSet.getMaxLoopDepth(block.getLabel());
         map.put(stmt.getIndex(), Math.pow(10, depth));
      }
      loopMultipliersProgram = program;
      loopMultipliersCache = map;
      return map;
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
      int score = 0;
      if(asm == null) asm = program.getAsm();
      Map<Integer, Double> multipliers = getLoopMultipliers(program);
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

   private static Map<VariableRef, List<Statement>> varStatementsCache = null;
   private static Program varStatementsProgram = null;

   private static synchronized Map<VariableRef, List<Statement>> getVarStatements(Program program) {
      if(varStatementsProgram == program && varStatementsCache != null) {
         return varStatementsCache;
      }
      Map<VariableRef, List<Statement>> map = new HashMap<>();
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
      varStatementsProgram = program;
      varStatementsCache = map;
      return map;
   }

   private static Map<ScopeRef, List<Graph.Block>> blocksByScopeCache = null;
   private static Program blocksByScopeProgram = null;

   private static synchronized Map<ScopeRef, List<Graph.Block>> getBlocksByScope(Program program) {
      if(blocksByScopeProgram == program && blocksByScopeCache != null) {
         return blocksByScopeCache;
      }
      Map<ScopeRef, List<Graph.Block>> map = new HashMap<>();
      for(Graph.Block b : program.getGraph().getAllBlocks()) {
         if(b.getScope() != null) {
            map.computeIfAbsent(b.getScope(), k -> new ArrayList<>()).add(b);
         }
      }
      blocksByScopeProgram = program;
      blocksByScopeCache = map;
      return map;
   }

   private static Map<dk.camelot64.kickc.model.values.LabelRef, List<Graph.Block>> predecessorsCache = null;
   private static Program predecessorsProgram = null;

   private static synchronized Map<dk.camelot64.kickc.model.values.LabelRef, List<Graph.Block>> getPredecessorsMap(Program program) {
      if(predecessorsProgram == program && predecessorsCache != null) {
         return predecessorsCache;
      }
      Map<dk.camelot64.kickc.model.values.LabelRef, List<Graph.Block>> map = new HashMap<>();
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
      predecessorsProgram = program;
      predecessorsCache = map;
      return map;
   }

   private static Map<dk.camelot64.kickc.model.values.LabelRef, Graph.Block> blocksByLabelCache = null;
   private static Program blocksByLabelProgram = null;

   private static synchronized Map<dk.camelot64.kickc.model.values.LabelRef, Graph.Block> getBlocksByLabel(Program program) {
      if(blocksByLabelProgram == program && blocksByLabelCache != null) {
         return blocksByLabelCache;
      }
      Map<dk.camelot64.kickc.model.values.LabelRef, Graph.Block> map = new HashMap<>();
      for(Graph.Block b : program.getGraph().getAllBlocks()) {
         map.put(b.getLabel(), b);
      }
      blocksByLabelProgram = program;
      blocksByLabelCache = map;
      return map;
   }

   public static Set<Graph.Block> getBlocksToRegenerate(
         Program program,
         ScopeRef scope,
         Collection<LiveRangeEquivalenceClass> equivalenceClasses) {
      Set<Graph.Block> blocks = new HashSet<>();
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
      Set<Graph.Block> neighbors = new HashSet<>();
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
         for(String unknownFragment : unknownFragments) {
            getLog().append("  " + unknownFragment);
         }
      }
      System.out.printf("      [Uplift Breakdown] %d attempts: blks=%.2fs, outScore=%.2fs, iter=%.2fs, overlap=%.2fs, codegen=%.2fs, clobber=%.2fs, score=%.2fs, update=%.2fs%n",
            countAttempts, timeBlocksNs/1e9, timeOutsideNs/1e9, timeIteratorNs/1e9, timeOverlapNs/1e9, timeCodeGenNs/1e9, timeClobberNs/1e9, timeScoreNs/1e9, timeUpdateNs/1e9);
      return cache;
   }

}
