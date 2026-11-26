package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.values.VariableRef;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

/**
 * Global cost-delta greedy coloring and budgeted 1-opt / 2-opt local search
 * for Pass 4 CPU register allocation.
 */
public class Pass4RegisterUpliftLocalSearch extends Pass2Base {

   private static final int MAX_PASSES = 4;
   private static final int TOP_K_NEIGHBORS = 8;

   public Pass4RegisterUpliftLocalSearch(Program program) {
      super(program);
   }

   public void performUplift() {
      Program program = getProgram();

      // Pre-initialize lazy analyses on the main thread before parallel Phase 1 evaluation
      LiveRangeInterference interference = program.getLiveRangeInterference();
      program.getPhiTransitions();
      program.getSymbolInfos();
      program.getStatementInfos();
      program.getLiveRangeVariablesEffective();
      program.getLoopSet();
      program.getVariableReferenceInfos();
      program.getCallGraph();
      Pass4DirectTrialEvaluator.getCpuSynthesizer(program);

      double[] stmtMultipliers = Pass4RegisterUpliftCombinations.getStmtMultipliers(program);
      Pass4DirectTrialEvaluator.FastClobberIndex clobberIndex = Pass4DirectTrialEvaluator.getOrBuildClobberIndex(program);
      clobberIndex.refreshFromVariables();

      // Initialize the All-ZP baseline once
      Map<Graph.Block, Integer> blockScores = new HashMap<>();
      Map<Graph.Block, Integer> blockBytes = new HashMap<>();
      Pass4DirectTrialEvaluator.computeBlockScores(
            program,
            program.getGraph().getAllBlocks(),
            stmtMultipliers,
            blockScores,
            blockBytes,
            clobberIndex,
            true);

      RegisterPotentials registerPotentials = program.getRegisterPotentials();
      VariableRegisterWeights weights = program.getVariableRegisterWeights();
      LiveRangeEquivalenceClassSet ecSet = program.getLiveRangeEquivalenceClassSet();

      List<LiveRangeEquivalenceClass> eligibleEcs = new ArrayList<>();
      for(LiveRangeEquivalenceClass ec : ecSet.getEquivalenceClasses()) {
         List<Registers.Register> pots = registerPotentials.getPotentialRegisters(ec);
         Registers.Register reg = ec.getRegister();
         if(pots != null && pots.size() > 1 && reg != null && reg.isMem() && reg.getBytes() == 1 && !reg.isAddressHardcoded()) {
            eligibleEcs.add(ec);
         }
      }

      eligibleEcs.sort((a, b) -> {
         int cmp = Double.compare(weights.getTotalWeight(b), weights.getTotalWeight(a));
         if(cmp != 0) return cmp;
         return getFirstVarName(a).compareTo(getFirstVarName(b));
      });

      int ecCount = eligibleEcs.size();
      if(ecCount == 0) {
         if(getLog().isVerboseUplift()) {
            System.out.printf("      [LocalSearch Uplift] E=0, attempts=0, phase2Commits=0, phase3Commits=0%n");
         }
         return;
      }

      Registers.Register[] defaultMemReg = new Registers.Register[ecCount];
      List<List<Registers.Register>> ecPotentials = new ArrayList<>(ecCount);
      Map<LiveRangeEquivalenceClass, Integer> ecToIdx = HashMap.newHashMap(ecCount);
      int maxPot = 0;
      for(int i = 0; i < ecCount; i++) {
         LiveRangeEquivalenceClass ec = eligibleEcs.get(i);
         defaultMemReg[i] = ec.getRegister();
         List<Registers.Register> pots = registerPotentials.getPotentialRegisters(ec);
         ecPotentials.add(pots);
         ecToIdx.put(ec, i);
         if(pots.size() > maxPot) {
            maxPot = pots.size();
         }
      }

      AtomicLong attempts = new AtomicLong(0);
      Set<String> unknownFragments = Collections.synchronizedSet(new LinkedHashSet<>());

      // Phase 1: Parallel standalone delta evaluation per (ec, R)
      Pass4DirectTrialEvaluator.DeltaEvalResult[][] standaloneDeltas = new Pass4DirectTrialEvaluator.DeltaEvalResult[ecCount][maxPot];
      IntStream.range(0, ecCount).parallel().forEach(ecIdx -> {
         LiveRangeEquivalenceClass ec = eligibleEcs.get(ecIdx);
         List<Registers.Register> pots = ecPotentials.get(ecIdx);
         for(int p = 0; p < pots.size(); p++) {
            Registers.Register reg = pots.get(p);
            if(!reg.isMem()) {
               attempts.incrementAndGet();
               standaloneDeltas[ecIdx][p] = Pass4DirectTrialEvaluator.evaluateDelta(
                     program, clobberIndex, stmtMultipliers, unknownFragments, ec, reg, null, null);
            }
         }
      });

      // Phase 2: Deterministic cost-delta greedy coloring
      int[] bestStandaloneScore = new int[ecCount];
      int[] bestStandaloneBytes = new int[ecCount];
      for(int ecIdx = 0; ecIdx < ecCount; ecIdx++) {
         Pass4DirectTrialEvaluator.DeltaEvalResult best = null;
         for(int p = 0; p < ecPotentials.get(ecIdx).size(); p++) {
            Pass4DirectTrialEvaluator.DeltaEvalResult res = standaloneDeltas[ecIdx][p];
            if(res != null && isBetterDelta(res, best)) {
               best = res;
            }
         }
         bestStandaloneScore[ecIdx] = best != null ? best.scoreDelta : 0;
         bestStandaloneBytes[ecIdx] = best != null ? best.bytesDelta : 0;
      }

      Integer[] phase2Order = new Integer[ecCount];
      for(int i = 0; i < ecCount; i++) {
         phase2Order[i] = i;
      }
      Arrays.sort(phase2Order, (a, b) -> {
         int cmp = Integer.compare(bestStandaloneScore[a], bestStandaloneScore[b]);
         if(cmp != 0) return cmp;
         cmp = Integer.compare(bestStandaloneBytes[a], bestStandaloneBytes[b]);
         if(cmp != 0) return cmp;
         cmp = Double.compare(weights.getTotalWeight(eligibleEcs.get(b)), weights.getTotalWeight(eligibleEcs.get(a)));
         if(cmp != 0) return cmp;
         return getFirstVarName(eligibleEcs.get(a)).compareTo(getFirstVarName(eligibleEcs.get(b)));
      });

      int phase2Commits = 0;
      for(int ord = 0; ord < ecCount; ord++) {
         int ecIdx = phase2Order[ord];
         LiveRangeEquivalenceClass ec = eligibleEcs.get(ecIdx);
         List<Registers.Register> pots = ecPotentials.get(ecIdx);
         Registers.Register bestReg = null;
         Pass4DirectTrialEvaluator.DeltaEvalResult bestRes = null;
         for(int p = 0; p < pots.size(); p++) {
            Registers.Register reg = pots.get(p);
            if(reg.isMem() || interference.interferesWithRegister(ec, reg)) {
               continue;
            }
            attempts.incrementAndGet();
            Pass4DirectTrialEvaluator.DeltaEvalResult res = Pass4DirectTrialEvaluator.evaluateDelta(
                  program, clobberIndex, stmtMultipliers, unknownFragments, ec, reg, null, null);
            if(isBetterDelta(res, bestRes)) {
               bestRes = res;
               bestReg = reg;
            }
         }
         if(bestReg != null) {
            Pass4DirectTrialEvaluator.commitMove(program, clobberIndex, stmtMultipliers, ec, bestReg, null, null);
            phase2Commits++;
         }
      }

      // Phase 3: Deterministic 1-opt & budgeted 2-opt
      int maxCommitsPerPass = Math.max(32, ecCount * 2);
      int phase3Commits = 0;
      for(int pass = 0; pass < MAX_PASSES; pass++) {
         int passCommits = 0;

         // 3a: 1-opt across all eligible ECs
         for(int ecIdx = 0; ecIdx < ecCount; ecIdx++) {
            if(passCommits >= maxCommitsPerPass) {
               break;
            }
            LiveRangeEquivalenceClass ec = eligibleEcs.get(ecIdx);
            Registers.Register curReg = ec.getRegister();
            List<Registers.Register> pots = ecPotentials.get(ecIdx);
            Registers.Register bestReg = null;
            Pass4DirectTrialEvaluator.DeltaEvalResult bestRes = null;

            for(int p = 0; p < pots.size(); p++) {
               Registers.Register reg = pots.get(p);
               if(Objects.equals(reg, curReg)) {
                  continue;
               }
               if(!reg.isMem() && interference.interferesWithRegister(ec, reg)) {
                  continue;
               }
               if(reg.isMem() && !Objects.equals(reg, defaultMemReg[ecIdx]) && interference.interferesWithRegister(ec, reg)) {
                  continue;
               }
               attempts.incrementAndGet();
               Pass4DirectTrialEvaluator.DeltaEvalResult res = Pass4DirectTrialEvaluator.evaluateDelta(
                     program, clobberIndex, stmtMultipliers, unknownFragments, ec, reg, null, null);
               if(isBetterDelta(res, bestRes)) {
                  bestRes = res;
                  bestReg = reg;
               }
            }

            if(bestReg != null) {
               Pass4DirectTrialEvaluator.commitMove(program, clobberIndex, stmtMultipliers, ec, bestReg, null, null);
               passCommits++;
               phase3Commits++;
            }
         }

         // 3b: Budgeted 2-opt Swap & Evict-and-Replace
         for(int ecIdx = 0; ecIdx < ecCount; ecIdx++) {
            if(passCommits >= maxCommitsPerPass) {
               break;
            }
            LiveRangeEquivalenceClass ecA = eligibleEcs.get(ecIdx);
            Registers.Register regA = ecA.getRegister();
            if(regA == null || regA.isMem()) {
               continue;
            }
            List<Registers.Register> potsA = ecPotentials.get(ecIdx);
            List<LiveRangeEquivalenceClass> rawNeighbors = interference.getInterferingClasses(ecA);
            if(rawNeighbors.isEmpty()) {
               continue;
            }

            List<Integer> neighborIndices = new ArrayList<>();
            for(LiveRangeEquivalenceClass ecB : rawNeighbors) {
               Integer ecBIdxObj = ecToIdx.get(ecB);
               if(ecBIdxObj == null) {
                  continue;
               }
               int ecBIdx = ecBIdxObj;
               Registers.Register regB = ecB.getRegister();
               if(regB == null) {
                  continue;
               }
               List<Registers.Register> potsB = ecPotentials.get(ecBIdx);
               if(!potsB.contains(regA)) {
                  continue;
               }
               if(regB.isMem() || (!regB.equals(regA) && potsA.contains(regB))) {
                  neighborIndices.add(ecBIdx);
               }
            }
            if(neighborIndices.isEmpty()) {
               continue;
            }

            neighborIndices.sort((idx1, idx2) -> {
               Pass4DirectTrialEvaluator.DeltaEvalResult d1 = findStandaloneDelta(ecPotentials.get(idx1), standaloneDeltas[idx1], regA);
               Pass4DirectTrialEvaluator.DeltaEvalResult d2 = findStandaloneDelta(ecPotentials.get(idx2), standaloneDeltas[idx2], regA);
               int s1 = (d1 != null && d1.feasible) ? d1.scoreDelta : Integer.MAX_VALUE;
               int s2 = (d2 != null && d2.feasible) ? d2.scoreDelta : Integer.MAX_VALUE;
               int cmp = Integer.compare(s1, s2);
               if(cmp != 0) return cmp;
               int b1 = (d1 != null && d1.feasible) ? d1.bytesDelta : Integer.MAX_VALUE;
               int b2 = (d2 != null && d2.feasible) ? d2.bytesDelta : Integer.MAX_VALUE;
               cmp = Integer.compare(b1, b2);
               if(cmp != 0) return cmp;
               cmp = Double.compare(weights.getTotalWeight(eligibleEcs.get(idx2)), weights.getTotalWeight(eligibleEcs.get(idx1)));
               if(cmp != 0) return cmp;
               return getFirstVarName(eligibleEcs.get(idx1)).compareTo(getFirstVarName(eligibleEcs.get(idx2)));
            });

            int limit = Math.min(TOP_K_NEIGHBORS, neighborIndices.size());
            LiveRangeEquivalenceClass bestEcB = null;
            Registers.Register bestNewRegA = null;
            Registers.Register bestNewRegB = null;
            Pass4DirectTrialEvaluator.DeltaEvalResult bestRes = null;

            for(int k = 0; k < limit; k++) {
               int ecBIdx = neighborIndices.get(k);
               LiveRangeEquivalenceClass ecB = eligibleEcs.get(ecBIdx);
               Registers.Register regB = ecB.getRegister();

               Registers.Register candNewRegA;
               if(regB.isMem()) {
                  // Evict-and-Replace: ecA -> defaultMemReg, ecB -> regA
                  candNewRegA = defaultMemReg[ecIdx];
                  if(interference.interferesWithRegisterIgnoring(ecB, regA, ecA)) {
                     continue;
                  }
               } else {
                  // Register-Swap: ecA -> regB, ecB -> regA
                  candNewRegA = regB;
                  if(interference.interferesWithRegisterIgnoring(ecA, regB, ecB)
                        || interference.interferesWithRegisterIgnoring(ecB, regA, ecA)) {
                     continue;
                  }
               }

               attempts.incrementAndGet();
               Pass4DirectTrialEvaluator.DeltaEvalResult res = Pass4DirectTrialEvaluator.evaluateDelta(
                     program, clobberIndex, stmtMultipliers, unknownFragments, ecA, candNewRegA, ecB, regA);
               if(isBetterDelta(res, bestRes)) {
                  bestRes = res;
                  bestEcB = ecB;
                  bestNewRegA = candNewRegA;
                  bestNewRegB = regA;
               }
            }

            if(bestEcB != null) {
               Pass4DirectTrialEvaluator.commitMove(
                     program, clobberIndex, stmtMultipliers, ecA, bestNewRegA, bestEcB, bestNewRegB);
               passCommits++;
               phase3Commits++;
            }
         }

         if(passCommits == 0) {
            break;
         }
      }

      if(!unknownFragments.isEmpty()) {
         getLog().append("MISSING FRAGMENTS");
         List<String> sortedUnknown = new ArrayList<>(unknownFragments);
         Collections.sort(sortedUnknown);
         for(String unknownFragment : sortedUnknown) {
            getLog().append("  " + unknownFragment);
         }
      }
      if(getLog().isVerboseUplift()) {
         System.out.printf(
               "      [LocalSearch Uplift] E=%d, attempts=%d, phase2Commits=%d, phase3Commits=%d%n",
               ecCount, attempts.get(), phase2Commits, phase3Commits);
      }
   }

   private static String getFirstVarName(LiveRangeEquivalenceClass ec) {
      List<VariableRef> vars = ec.getVariables();
      return vars.isEmpty() ? "" : vars.get(0).getFullName();
   }

   private static Pass4DirectTrialEvaluator.DeltaEvalResult findStandaloneDelta(
         List<Registers.Register> pots,
         Pass4DirectTrialEvaluator.DeltaEvalResult[] deltas,
         Registers.Register reg) {
      for(int p = 0; p < pots.size(); p++) {
         if(Objects.equals(pots.get(p), reg)) {
            return deltas[p];
         }
      }
      return null;
   }

   private static boolean isBetterDelta(
         Pass4DirectTrialEvaluator.DeltaEvalResult cand,
         Pass4DirectTrialEvaluator.DeltaEvalResult best) {
      if(!cand.feasible) {
         return false;
      }
      if(best == null) {
         return cand.isStrictlyBetter();
      }
      if(cand.scoreDelta != best.scoreDelta) {
         return cand.scoreDelta < best.scoreDelta;
      }
      if(cand.bytesDelta != best.bytesDelta) {
         return cand.bytesDelta < best.bytesDelta;
      }
      return cand.cpuRegDelta > best.cpuRegDelta;
   }
}
