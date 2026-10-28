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

   public static void chooseBestUpliftCombination(
         RegisterCombinationIterator combinationIterator, int maxCombinations,
         Set<String> unknownFragments,
         ScopeRef scope,
         Program program,
         CachedBlockScores cache
   ) {
      int bestScore = Integer.MAX_VALUE;
      RegisterCombination bestCombination = null;
      int countCombinations = 0;

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

      while(true) {
         long tIt = System.nanoTime();
         boolean hasNext = combinationIterator.hasNext();
         timeIteratorNs += (System.nanoTime() - tIt);
         if(!hasNext || countCombinations >= maxCombinations) break;

         countCombinations++;
         if(countCombinations % 10000 == 0) {
            program.getLog().append("Uplift attempts [" + scope + "] " + countCombinations + "/" + combinationIterator.getNumIterations() + " (limiting to " + maxCombinations + ")");
         }
         tIt = System.nanoTime();
         RegisterCombination combination = combinationIterator.next();
         timeIteratorNs += (System.nanoTime() - tIt);

         if(!generateCombinationAsm(combination, program, unknownFragments, scope, blocksToRegenerate, cache.cachedBlockChunks)) {
            continue;
         }
         long tSc = System.nanoTime();
         // If no clobber - Find value of the resulting allocation
         int combinationScore = outsideScore + getAsmScore(program);
         timeScoreNs += (System.nanoTime() - tSc);

         if(program.getLog().isVerboseUplift()) {
            StringBuilder msg = new StringBuilder();
            msg.append("Uplift attempt [" + scope + "] ");
            msg.append(combinationScore);
            msg.append(" allocation: ").append(combination.toString());
            program.getLog().append(msg.toString());
         }
         if(combinationScore < bestScore) {
            bestScore = combinationScore;
            bestCombination = combination;
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

   /**
    * Attempt generate ASM with a specific register combination.
    * The generation may result in failure if
    * <ul>
    * <li>The combination has assigned the same register to variables with overlapping live ranges</li>
    * <li>The register combination results in clobbering registers containing values that are still alive</li>
    * <li>some ASM fragments are missing </li>
    * <li>the ALU register is used in a non-applicable way</li>
    * </ul>
    *
    * @param combination The register allocation combination
    * @param unknownFragments Will receive any AsmFragments that can not be found during the ASM code generation
    * @param scope The scope where the combination is tested. (Only used for logging)
    * @return true if the generation was successful
    */
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
      countAttempts++;
      // Save original registers of the combination
      Map<LiveRangeEquivalenceClass, Registers.Register> oldRegisters = new HashMap<>();
      for(LiveRangeEquivalenceClass ec : combination.getAllocation().keySet()) {
         oldRegisters.put(ec, ec.getRegister());
      }
      // Apply the uplift combination
      combination.allocate(program);
      try {
         long tA = System.nanoTime();
         // Check the register allocation for whether any register is being allocated to two variables with overlapping live ranges
         if(isAllocationOverlapping(program, combination)) {
            timeOverlapNs += (System.nanoTime() - tA);
            if(program.getLog().isVerboseUplift()) {
               StringBuilder msg = new StringBuilder();
               msg.append("Uplift attempt [" + (scope == null ? "" : scope) + "] ");
               msg.append("overlapping");
               msg.append(" allocation: ").append(combination.toString());
               program.getLog().append(msg.toString());
            }

            return false;
         }
         timeOverlapNs += (System.nanoTime() - tA);
         // Generate ASM
         if(blocksToRegenerate == null) {
            blocksToRegenerate = getBlocksToRegenerate(program, scope, combination.getAllocation().keySet());
         }
         long tB = System.nanoTime();
         try {
            new Pass4CodeGeneration(program, false, program.isWarnFragmentMissing(), true).generate(blocksToRegenerate, cachedBlockChunks, false);
         } catch(AsmFragmentTemplateSynthesizer.UnknownFragmentException e) {
            timeCodeGenNs += (System.nanoTime() - tB);
            unknownFragments.add(e.getFragmentSignature());
            if(program.getLog().isVerboseUplift()) {
               StringBuilder msg = new StringBuilder();
               msg.append("Uplift attempt [" + (scope == null ? "" : scope) + "] ");
               msg.append("missing fragment " + e.getFragmentSignature());
               msg.append(" allocation: ").append(combination.toString());
               program.getLog().append(msg.toString());
            }
            return false;
         }
         timeCodeGenNs += (System.nanoTime() - tB);
         long tC = System.nanoTime();
         boolean hasClobberProblem = new Pass4AssertNoCpuClobber(program).hasClobberProblem(false);
         timeClobberNs += (System.nanoTime() - tC);
         if(hasClobberProblem) {
            if(program.getLog().isVerboseUplift()) {
               StringBuilder msg = new StringBuilder();
               msg.append("Uplift attempt [" + (scope == null ? "" : scope) + "] ");
               msg.append("clobber");
               msg.append(" allocation: ").append(combination.toString());
               program.getLog().append(msg.toString());
            }
            return false;
         }
         return true;
      } finally {
         for(LiveRangeEquivalenceClass ec : combination.getAllocation().keySet()) {
            Registers.Register oldReg = oldRegisters.get(ec);
            ec.setRegister(oldReg);
            for(VariableRef varRef : ec.getVariables()) {
               program.getScope().getVariable(varRef).setAllocation(oldReg);
            }
         }
      }
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
      int score = 0;
      AsmProgram asm = program.getAsm();
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
      Set<String> unknownFragments = new LinkedHashSet<>();
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
