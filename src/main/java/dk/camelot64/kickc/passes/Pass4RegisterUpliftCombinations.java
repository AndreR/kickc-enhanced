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
   static void chooseBestUpliftCombination(
         RegisterCombinationIterator combinationIterator, int maxCombinations,
         Set<String> unknownFragments,
         ScopeRef scope,
         Program program
   ) {
      chooseBestUpliftCombination(combinationIterator, maxCombinations, unknownFragments, scope, program, null);
   }

   static void chooseBestUpliftCombination(
         RegisterCombinationIterator combinationIterator, int maxCombinations,
         Set<String> unknownFragments,
         ScopeRef scope,
         Program program,
         Map<Graph.Block, List<AsmChunk>> cachedBlockChunks
   ) {
      int bestScore = Integer.MAX_VALUE;
      RegisterCombination bestCombination = null;
      int countCombinations = 0;

      if(cachedBlockChunks == null) {
         cachedBlockChunks = new HashMap<>();
         new Pass4CodeGeneration(program, false, program.isWarnFragmentMissing(), true).generate(null, cachedBlockChunks, true);
      }
      Set<Graph.Block> blocksToRegenerate = getBlocksToRegenerate(program, scope, combinationIterator.getEquivalenceClasses());

      int outsideScore = 0;
      if(cachedBlockChunks != null) {
         Map<Integer, Double> multipliers = getLoopMultipliers(program);
         for(Map.Entry<Graph.Block, List<AsmChunk>> entry : cachedBlockChunks.entrySet()) {
            if(!blocksToRegenerate.contains(entry.getKey())) {
               for(AsmChunk chunk : entry.getValue()) {
                  double cycles = chunk.getCycles();
                  if(cycles > 0) {
                     Integer statementIdx = chunk.getStatementIdx();
                     Double mult = statementIdx == null ? 10.0 : multipliers.get(statementIdx);
                     outsideScore += cycles * (mult == null ? 10.0 : mult);
                  }
               }
            }
         }
      }

      while(combinationIterator.hasNext() && countCombinations < maxCombinations) {
         countCombinations++;
         if(countCombinations % 10000 == 0) {
            program.getLog().append("Uplift attempts [" + scope + "] " + countCombinations + "/" + combinationIterator.getNumIterations() + " (limiting to " + maxCombinations + ")");
         }
         RegisterCombination combination = combinationIterator.next();
         if(!generateCombinationAsm(combination, program, unknownFragments, scope, blocksToRegenerate, cachedBlockChunks)) {
            continue;
         }
         // If no clobber - Find value of the resulting allocation
         int combinationScore = outsideScore + getAsmScore(program);
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
         program.getLog().append("Uplifting [" + scope + "] best " + bestScore + " combination " + bestCombination.toString());
         new Pass4CodeGeneration(program, false, program.isWarnFragmentMissing(), true).generate(blocksToRegenerate, cachedBlockChunks, true);
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

   public static boolean generateCombinationAsm(
         RegisterCombination combination,
         Program program,
         Set<String> unknownFragments,
         ScopeRef scope,
         Set<Graph.Block> blocksToRegenerate,
         Map<Graph.Block, List<AsmChunk>> cachedBlockChunks) {
      // Save original registers of the combination
      Map<LiveRangeEquivalenceClass, Registers.Register> oldRegisters = new HashMap<>();
      for(LiveRangeEquivalenceClass ec : combination.getAllocation().keySet()) {
         oldRegisters.put(ec, ec.getRegister());
      }
      // Apply the uplift combination
      combination.allocate(program);
      try {
         // Check the register allocation for whether any register is being allocated to two variables with overlapping live ranges
         if(isAllocationOverlapping(program, combination)) {
            if(program.getLog().isVerboseUplift()) {
               StringBuilder msg = new StringBuilder();
               msg.append("Uplift attempt [" + (scope == null ? "" : scope) + "] ");
               msg.append("overlapping");
               msg.append(" allocation: ").append(combination.toString());
               program.getLog().append(msg.toString());
            }

            return false;
         }
         // Generate ASM
         try {
            new Pass4CodeGeneration(program, false, program.isWarnFragmentMissing(), true).generate(blocksToRegenerate, cachedBlockChunks, false);
         } catch(AsmFragmentTemplateSynthesizer.UnknownFragmentException e) {
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
         boolean hasClobberProblem = new Pass4AssertNoCpuClobber(program).hasClobberProblem(false);
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

   public static Set<Graph.Block> getBlocksToRegenerate(
         Program program,
         ScopeRef scope,
         Collection<LiveRangeEquivalenceClass> equivalenceClasses) {
      Set<Graph.Block> blocks = new HashSet<>();
      if(scope != null) {
         for(Graph.Block block : program.getGraph().getAllBlocks()) {
            if(scope.equals(block.getScope())) {
               blocks.add(block);
            }
         }
      }
      Map<VariableRef, List<Statement>> varStatements = getVarStatements(program);
      if(equivalenceClasses != null) {
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
      Set<Graph.Block> neighbors = new HashSet<>();
      for(Graph.Block b : blocks) {
         neighbors.addAll(program.getGraph().getPredecessors(b));
         for(dk.camelot64.kickc.model.values.LabelRef succ : b.getSuccessors()) {
            Graph.Block succBlock = program.getGraph().getBlock(succ);
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
      return isAllocationOverlapping(program, null);
   }

   public static boolean isAllocationOverlapping(Program program, RegisterCombination combination) {
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

   public void performUplift(int maxCombinations) {
      // Test uplift combinations to find the best one.
      Set<String> unknownFragments = new LinkedHashSet<>();
      Map<Graph.Block, List<AsmChunk>> cachedBlockChunks = new HashMap<>();
      new Pass4CodeGeneration(getProgram(), false, getProgram().isWarnFragmentMissing(), true).generate(null, cachedBlockChunks, true);

      List<RegisterUpliftScope> registerUpliftScopes = getProgram().getRegisterUpliftProgram().getRegisterUpliftScopes();
      for(RegisterUpliftScope upliftScope : registerUpliftScopes) {
         RegisterCombinationIterator combinationIterator = upliftScope.getCombinationIterator(getProgram().getRegisterPotentials());
         chooseBestUpliftCombination(
               combinationIterator,
               maxCombinations,
               unknownFragments,
               upliftScope.getScopeRef(),
               getProgram(),
               cachedBlockChunks);
      }

      if(unknownFragments.size() > 0) {
         getLog().append("MISSING FRAGMENTS");
         for(String unknownFragment : unknownFragments) {
            getLog().append("  " + unknownFragment);
         }
      }
   }

}
